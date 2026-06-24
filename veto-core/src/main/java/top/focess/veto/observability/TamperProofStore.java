package top.focess.veto.observability;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.model.AuditRecord;

/**
 * observability Tamper-Proof Store - encrypted, hash-chained persistent audit log. Each entry
 * includes the previous entry's hash, forming an immutable chain. Logs are encrypted at rest using
 * AES-256-GCM.
 */
@Component
public class TamperProofStore {

    private static final Logger log = LoggerFactory.getLogger(TamperProofStore.class);

    private static final String STORE_FILE_PREFIX = "veto-audit-";
    private static final String STORE_FILE_SUFFIX = ".enc";
    private static final String INDEX_FILE = "veto-audit-index";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private final ObservabilityConfiguration config;
    private final Path auditDir;
    private final DiffCalculator diffCalculator;

    // In-memory hash chain tail (last record's hash for integrity)
    private volatile String chainTailHash = "";

    public TamperProofStore(ObservabilityConfiguration config, DiffCalculator diffCalculator) {
        this.config = config;
        this.auditDir = Path.of(config.getAuditLogPath());
        this.diffCalculator = diffCalculator;
    }

    /** Initialize the tamper-proof store. Creates directories and loads chain tail. */
    public void initialize() {
        try {
            Files.createDirectories(auditDir);
            loadChainTail();
            log.info(
                    "observability TamperProofStore: Initialized at '{}'. Chain tail hash: {}",
                    auditDir,
                    chainTailHash.isEmpty()
                            ? "(new chain)"
                            : chainTailHash.substring(0, 16) + "...");
        } catch (IOException e) {
            log.error("observability TamperProofStore: Cannot initialize", e);
        }
    }

    /**
     * Append an audit record to the tamper-proof store. The record is encrypted and written to the
     * daily audit file. The hash chain is maintained in memory and persisted in a compact index.
     */
    public synchronized void append(AuditRecord record) {
        try {
            // Verify previous record's hash chain
            if (!chainTailHash.isEmpty() && !record.getPreviousRecordHash().equals(chainTailHash)) {
                log.warn(
                        "observability TamperProofStore: Hash chain broken! Expected tail '{}', got '{}'",
                        chainTailHash,
                        record.getPreviousRecordHash());
            }

            // Serialize + encrypt
            String recordJson = serializeRecord(record);
            byte[] encrypted = encryptRecord(recordJson);

            // Write to daily file
            Path dailyFile = getDailyFilePath();
            Files.write(dailyFile, encrypted, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // Write separator
            Files.write(
                    dailyFile,
                    "\n---RECORD_BOUNDARY---\n".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);

            // Update chain tail
            chainTailHash = record.getCurrentHash();

            // Update compact index
            appendToIndex(record);

            log.debug(
                    "observability TamperProofStore: Appended record '{}' (action={})",
                    record.getId(),
                    record.getAction());

        } catch (Exception e) {
            log.error("observability TamperProofStore: Failed to append audit record", e);
        }
    }

    /** Verify the integrity of the entire audit chain from a given start. */
    public ChainVerificationResult verifyChain() {
        List<String> violations = new ArrayList<>();
        int recordsChecked = 0;
        String previousHash = "";

        try {
            Path indexFile = auditDir.resolve(INDEX_FILE);
            if (!Files.exists(indexFile)) {
                return new ChainVerificationResult(0, true, "No index file exists (empty chain)");
            }

            try (BufferedReader reader = Files.newBufferedReader(indexFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|");
                    if (parts.length >= 3) {
                        String recordId = parts[0];
                        String recordHash = parts[1];
                        String prevHash = parts[2];
                        recordsChecked++;

                        if (!prevHash.equals(previousHash)) {
                            violations.add(
                                    "Chain break at record "
                                            + recordId
                                            + ": expected prev="
                                            + previousHash.substring(
                                                    0, Math.min(8, previousHash.length()))
                                            + ", got "
                                            + prevHash.substring(
                                                    0, Math.min(8, prevHash.length())));
                        }
                        previousHash = recordHash;
                    }
                }
            }
        } catch (IOException e) {
            return new ChainVerificationResult(
                    recordsChecked, false, "I/O error during verification: " + e.getMessage());
        }

        boolean intact = violations.isEmpty();
        String summary =
                intact
                        ? String.format("Chain intact: %d records verified", recordsChecked)
                        : String.format(
                                "Chain BROKEN: %d violations in %d records",
                                violations.size(), recordsChecked);

        return new ChainVerificationResult(recordsChecked, intact, summary);
    }

    /** Export audit records for a date range. Requires the store encryption key. */
    public List<String> exportRecords(LocalDate from, LocalDate to) {
        List<String> records = new ArrayList<>();
        try {
            try (DirectoryStream<Path> stream =
                    Files.newDirectoryStream(
                            auditDir, STORE_FILE_PREFIX + "*" + STORE_FILE_SUFFIX)) {
                for (Path file : stream) {
                    String fileName = file.getFileName().toString();
                    LocalDate fileDate = extractDate(fileName);
                    if (fileDate != null && !fileDate.isBefore(from) && !fileDate.isAfter(to)) {
                        byte[] encrypted = Files.readAllBytes(file);
                        String decrypted = decryptRecord(encrypted);
                        records.add(decrypted);
                    }
                }
            }
        } catch (Exception e) {
            log.error("observability TamperProofStore: Export error", e);
        }
        return records;
    }

    private String serializeRecord(AuditRecord record) {
        return String.join(
                "|",
                record.getId(),
                record.getDagPayloadId(),
                record.getRequestId(),
                record.getComponentSource(),
                record.getRawPayloadHash(),
                record.getRedactedPayloadHash(),
                record.getDiffExcerpt(),
                record.getPreviousRecordHash(),
                record.getCurrentHash(),
                record.getTimestamp().toString(),
                record.getAction().name(),
                String.valueOf(record.isVetoApplied()));
    }

    private byte[] encryptRecord(String plaintext) throws Exception {
        if (!config.isEncryptionEnabled()) {
            return plaintext.getBytes(StandardCharsets.UTF_8);
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom.getInstanceStrong().nextBytes(iv);

        SecretKey key = deriveStoreKey();
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined).getBytes(StandardCharsets.UTF_8);
    }

    private String decryptRecord(byte[] encryptedBlob) throws Exception {
        if (!config.isEncryptionEnabled()) {
            return new String(encryptedBlob, StandardCharsets.UTF_8);
        }

        String b64 = new String(encryptedBlob, StandardCharsets.UTF_8).trim();
        byte[] combined = Base64.getDecoder().decode(b64);

        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

        SecretKey key = deriveStoreKey();
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private SecretKey deriveStoreKey() {
        try {
            // Use SHA-256 to derive a 256-bit key from the configured password/key
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes =
                    digest.digest(config.getEncryptionKey().getBytes(StandardCharsets.UTF_8));
            return new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private Path getDailyFilePath() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return auditDir.resolve(STORE_FILE_PREFIX + dateStr + STORE_FILE_SUFFIX);
    }

    private void appendToIndex(AuditRecord record) throws IOException {
        Path indexFile = auditDir.resolve(INDEX_FILE);
        String indexLine =
                String.join(
                        "|",
                        record.getId(),
                        record.getCurrentHash(),
                        record.getPreviousRecordHash(),
                        record.getTimestamp().toString(),
                        record.getAction().name());
        Files.writeString(
                indexFile, indexLine + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void loadChainTail() {
        Path indexFile = auditDir.resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) return;

        try {
            // Read last line of index
            String lastLine = "";
            try (BufferedReader reader = Files.newBufferedReader(indexFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastLine = line;
                }
            }
            if (!lastLine.isEmpty()) {
                String[] parts = lastLine.split("\\|");
                if (parts.length >= 2) {
                    chainTailHash = parts[1];
                }
            }
        } catch (IOException e) {
            log.warn("observability TamperProofStore: Cannot load chain tail", e);
        }
    }

    private LocalDate extractDate(String fileName) {
        try {
            String dateStr = fileName.replace(STORE_FILE_PREFIX, "").replace(STORE_FILE_SUFFIX, "");
            return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    public String getChainTailHash() {
        return chainTailHash;
    }

    /** Result of an audit chain verification. */
    public record ChainVerificationResult(
            int recordsChecked, boolean chainIntact, String summary) {}
}
