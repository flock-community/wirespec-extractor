package community.flock.wirespec.extractor.scan

import io.github.classgraph.ClassInfo

/**
 * True when this is a type someone actually declared, rather than one the
 * compiler generated: anonymous objects, lambdas, local classes and synthetic
 * helpers.
 *
 * The scanners that recognise a candidate from its bytecode — Ktor clients,
 * Ktor routing, the Spring functional DSL — see those generated classes too,
 * and a generated class inherits the framework references of the code it was
 * lifted out of. A lambda inside an `inline suspend fun` wrapping
 * `client.get { }`, for instance, captures the enclosing `HttpRequestBuilder`
 * and so looks exactly like a Ktor client.
 *
 * They must be dropped before the result is used: such a class has no
 * source-level name (`Class.getSimpleName()` is empty), so it collides with
 * every other generated class in the run and would name its .ws file and
 * definitions after nothing.
 */
internal fun ClassInfo.isDeclaredType(): Boolean = !isAnonymousInnerClass && !isSynthetic
