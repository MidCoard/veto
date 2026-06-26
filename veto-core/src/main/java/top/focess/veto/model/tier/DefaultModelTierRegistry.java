package top.focess.veto.model.tier;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.vault.CredentialVault;

@Service
public class DefaultModelTierRegistry implements ModelTierRegistry {

    @Autowired private AgentPatternRepository patternRepo;

    @Autowired private CredentialVault vault;

    @Override
    public ModelBinding resolve(String patternName, ModelTier tier) {
        if (tier == ModelTier.LOCAL) {
            return new ModelBinding(Provider.LOCAL_SLM, "local-slm", 0.1, 2048);
        }

        String user = vault.getCurrentUser();
        if (user != null && patternName != null) {
            Optional<AgentPatternEntity> patternOpt =
                    patternRepo.findByNameAndOwner(patternName, user);
            if (patternOpt.isPresent()) {
                AgentPatternEntity p = patternOpt.get();
                String top = p.getTopModel() != null ? p.getTopModel() : p.getModel();
                String modelId;
                if (tier == ModelTier.TOP) {
                    modelId = top;
                } else if (tier == ModelTier.MID) {
                    modelId = p.getMidModel() != null ? p.getMidModel() : top;
                } else { // LOW
                    modelId = p.getLowModel() != null ? p.getLowModel() : top;
                }
                return getFromCatalog(modelId);
            }
        }

        // Fallback or default
        return getFromCatalog(null);
    }

    @Override
    public VaultCredentialRef credential(String userId, Provider provider) {
        String key = "credential-" + provider.name().toLowerCase();
        if (patternRepo != null) {
            List<AgentPatternEntity> patterns = patternRepo.findByOwner(userId);
            for (AgentPatternEntity p : patterns) {
                if (p.getProvider().equalsIgnoreCase(provider.name())) {
                    key = p.getCredentialKey();
                    break;
                }
            }
        }
        return new VaultCredentialRef(userId, provider, key);
    }

    private ModelBinding getFromCatalog(String modelId) {
        if (modelId == null) {
            return new ModelBinding(Provider.GEMINI, "gemini-3.5-flash", 0.7, 8192);
        }
        String idLower = modelId.toLowerCase();
        if (idLower.contains("gpt")) {
            return new ModelBinding(Provider.OPENAI, modelId, 0.7, 4096);
        } else if (idLower.contains("claude")) {
            return new ModelBinding(Provider.ANTHROPIC, modelId, 0.7, 4096);
        } else if (idLower.contains("gemini")) {
            return new ModelBinding(Provider.GEMINI, modelId, 0.7, 8192);
        } else if (idLower.contains("deepseek")) {
            return new ModelBinding(Provider.DEEPSEEK, modelId, 0.7, 4096);
        } else if (idLower.contains("local-slm") || idLower.contains("slm")) {
            return new ModelBinding(Provider.LOCAL_SLM, modelId, 0.1, 2048);
        }
        return new ModelBinding(Provider.GEMINI, modelId, 0.7, 4096);
    }
}
