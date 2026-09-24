package top.focess.veto.distribution;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/** Bundled feature names are a distribution choice, below explicit operator configuration. */
@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:veto-distribution.properties")
public class DistributionConfiguration {}
