package org.incodame.pajes.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an implementation method whose configured parameter and return annotations
 * should be emitted as RDF constraints without requiring a unique API override.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface ApiImplementation {
}
