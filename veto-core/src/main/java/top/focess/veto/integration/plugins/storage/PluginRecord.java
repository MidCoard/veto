package top.focess.veto.integration.plugins.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.NonNull;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.vault.UserEntity;

/** Host-owned addressing only; the document remains opaque plugin data. */
@Entity
@Table(
        name = "plugin_records",
        uniqueConstraints =
                @UniqueConstraint(
                        columnNames = {"plugin_id", "scope_kind", "scope_id", "entry_key"}))
public class PluginRecord {
    @Id @NonNull String id = "";

    @Column(name = "plugin_id", nullable = false)
    @NonNull String plugin = "";

    @Column(name = "scope_kind", nullable = false)
    @NonNull String kind = "";

    @Column(name = "scope_id", nullable = false)
    @NonNull String scope = "";

    @Column(name = "entry_key", nullable = false, length = 256)
    @NonNull String key = "";

    @Column(nullable = false)
    @NonNull String revision = "";

    @Column(nullable = false)
    int schemaVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    @NonNull String payload = "";

    @ManyToOne
    @JoinColumn(name = "user_id", referencedColumnName = "storage_identity")
    @OnDelete(action = OnDeleteAction.CASCADE)
    UserEntity user;

    @ManyToOne
    @JoinColumn(name = "session_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    SessionEntity session;

    /** JPA provider constructor. */
    protected PluginRecord() {}
}
