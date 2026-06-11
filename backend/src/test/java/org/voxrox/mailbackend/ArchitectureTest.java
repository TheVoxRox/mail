package org.voxrox.mailbackend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;

import java.util.concurrent.Future;

import org.springframework.scheduling.annotation.Async;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Project conventions as executable rules. Each rule encodes a contract that
 * was previously enforced only by review — and that past reviews caught being
 * violated silently. When a rule fails, fix the code; only scope the rule down
 * when the architecture decision itself changes (and document why here).
 */
@AnalyzeClasses(packages = "org.voxrox.mailbackend", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * SLF4J everywhere. The single sanctioned exception is the bootstrap class:
     * {@code main()} reports port/argument failures to stderr BEFORE logging is
     * configured (the jpackage launcher and the Tauri sidecar capture stderr).
     */
    @ArchTest
    static final ArchRule noStandardStreams = noClasses().that().doNotBelongToAnyOf(MailBackendApplication.class)
            .should(ACCESS_STANDARD_STREAMS)
            .because("SLF4J is the logging API; stderr is allowed only in the pre-logging bootstrap");

    @ArchTest
    static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noJodaTime = NO_CLASSES_SHOULD_USE_JODATIME;

    /**
     * Controllers orchestrate via services. Direct repository access from a
     * controller bypasses validation, audit logging and transactional boundaries
     * that live in the service layer. (Spring Security's
     * {@code ClientRegistrationRepository} is unaffected — the rule only covers
     * this project's repository packages.)
     */
    @ArchTest
    static final ArchRule controllersDoNotTouchRepositories = noClasses().that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("org.voxrox.mailbackend..repository..")
            .because("controllers must go through the service layer (validation, audit, transactions)");

    /**
     * The pooled IMAP {@link jakarta.mail.Store} is not thread-safe; every access
     * must run under the per-account lock owned by
     * {@code ImapConnectionManager.executeWithLock}. Letting another package obtain
     * a Store reference would bypass that lock — exactly the bug class behind the
     * 2026-06 connection-corruption findings.
     */
    @ArchTest
    static final ArchRule imapStoreStaysInMailServicePackage = noClasses().that()
            .resideOutsideOfPackage("..feature.mail.service..").should().dependOnClassesThat()
            .haveFullyQualifiedName("jakarta.mail.Store")
            .because("Store access must stay behind ImapConnectionManager's per-account lock");

    /**
     * Spring executes {@code @Async} methods on the executor and silently discards
     * any return value that is not a {@link Future} — a non-void, non-Future async
     * method looks like it works but the caller can never see the result.
     */
    @ArchTest
    static final ArchRule asyncMethodsReturnVoidOrFuture = methods().that().areAnnotatedWith(Async.class).should()
            .haveRawReturnType(DescribedPredicate.describe("void or Future",
                    (JavaClass type) -> type.isEquivalentTo(void.class) || type.isAssignableTo(Future.class)))
            .because("Spring silently drops non-Future return values of @Async methods");
}
