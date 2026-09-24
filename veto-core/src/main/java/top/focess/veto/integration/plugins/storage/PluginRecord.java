package top.focess.veto.integration.plugins.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.vault.UserEntity;

/** Host-owned addressing only; the document remains opaque plugin data. */
@Entity
@Table(
        name = "plugin_records",
        uniqueConstraints =
                @UniqueConstraint(
                        columnNames = {"plugin_id", "scope_kind", "scope_id", "entry_key"}))
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public class PluginRecord {
    @Id String id = "";

    @Column(name = "plugin_id", nullable = false)
    String plugin = "";

    @Column(name = "scope_kind", nullable = false)
    String kind = "";

    @Column(name = "scope_id", nullable = false)
    String scope = "";

    @Column(name = "entry_key", nullable = false, length = 256)
    String key = "";

    @Column(nullable = false)
    String revision = "";

    @Column(nullable = false)
    int schemaVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    String payload = "";

    @ManyToOne
    @JoinColumn(name = "owner_username")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @Nullable UserEntity user;

    @ManyToOne
    @JoinColumn(name = "session_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @Nullable SessionEntity session;

    protected PluginRecord() {}
}
