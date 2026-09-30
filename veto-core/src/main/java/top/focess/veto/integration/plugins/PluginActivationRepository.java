package top.focess.veto.integration.plugins;

import org.springframework.data.jpa.repository.JpaRepository;

/** Durable desired activation state, keyed by plugin identity. */
public interface PluginActivationRepository extends JpaRepository<PluginActivationEntity, String> {}
