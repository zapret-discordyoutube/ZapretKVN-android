package io.github.zapretkvn.android.apps

internal data class DiscoveredApplication(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
    val rawFailures: List<String> = emptyList(),
)

internal enum class AppDiscoverySourceId {
    InstalledApplications,
    InstalledPackages,
    Launcher,
    LeanbackLauncher,
}

internal enum class AppDiscoverySourceOutcome {
    Success,
    PermissionDenied,
    PlatformUnavailable,
    UnexpectedFailure,
    NotRequired,
}

internal data class AppDiscoverySourceReport(
    val source: AppDiscoverySourceId,
    val outcome: AppDiscoverySourceOutcome,
    val itemCount: Int,
    val rawFailure: String? = null,
)

internal enum class AppDiscoveryCompleteness {
    Complete,
    Recovered,
    Partial,
    Failed,
}

internal data class AppDiscoverySummary(
    val completeness: AppDiscoveryCompleteness,
    val sources: List<AppDiscoverySourceReport>,
) {
    fun rawProblemText(): String? {
        val hasFailure = sources.any { it.rawFailure != null }
        if (!hasFailure &&
            completeness != AppDiscoveryCompleteness.Partial &&
            completeness != AppDiscoveryCompleteness.Failed
        ) {
            return null
        }
        return sources.joinToString(separator = "\n", transform = AppDiscoverySourceReport::rawText)
    }
}

internal data class AppDiscoveryReport(
    val applications: List<DiscoveredApplication>,
    val summary: AppDiscoverySummary,
)

internal fun interface AppDiscoverySource {
    fun discover(): List<DiscoveredApplication>
}

internal data class NamedAppDiscoverySource(
    val id: AppDiscoverySourceId,
    val source: AppDiscoverySource,
)

internal class AppDiscoveryCoordinator(
    private val ownPackageName: String,
    private val primaryInventory: NamedAppDiscoverySource,
    private val fallbackInventory: NamedAppDiscoverySource,
    private val supplements: List<NamedAppDiscoverySource>,
) {
    fun discover(): AppDiscoveryReport {
        val primary = execute(primaryInventory)
        val supplementResults = supplements.map(::execute)
        val primaryPackages = primary.applications.packageNames()
        val supplementPackages = supplementResults
            .asSequence()
            .flatMap { it.applications.asSequence() }
            .map(DiscoveredApplication::packageName)
            .toSet()
        val supplementFailed = supplementResults.any {
            it.report.outcome != AppDiscoverySourceOutcome.Success
        }
        val fallbackRequired = primary.report.outcome != AppDiscoverySourceOutcome.Success ||
            (primaryPackages - ownPackageName).isEmpty() ||
            supplementFailed ||
            !primaryPackages.containsAll(supplementPackages)
        val fallback = if (fallbackRequired) {
            execute(fallbackInventory)
        } else {
            SourceResult(
                applications = emptyList(),
                report = AppDiscoverySourceReport(
                    source = fallbackInventory.id,
                    outcome = AppDiscoverySourceOutcome.NotRequired,
                    itemCount = 0,
                ),
            )
        }

        val merged = linkedMapOf<String, DiscoveredApplication>()
        sequenceOf(primary, fallback)
            .plus(supplementResults.asSequence())
            .flatMap { it.applications.asSequence() }
            .forEach { application ->
                merged.putIfAbsent(application.packageName, application)
            }

        val inventoryPackages = sequenceOf(primary, fallback)
            .flatMap { it.applications.asSequence() }
            .map(DiscoveredApplication::packageName)
            .toSet()
        val fallbackRecoveredInventory =
            fallback.report.outcome == AppDiscoverySourceOutcome.Success &&
                inventoryPackages.containsAll(supplementPackages)
        val allResults = sequenceOf(primary, fallback).plus(supplementResults.asSequence())
        val completeness = when {
            allResults.none { it.report.outcome == AppDiscoverySourceOutcome.Success } ->
                AppDiscoveryCompleteness.Failed

            !fallbackRequired &&
                primary.report.outcome == AppDiscoverySourceOutcome.Success ->
                AppDiscoveryCompleteness.Complete

            fallbackRequired && fallbackRecoveredInventory ->
                AppDiscoveryCompleteness.Recovered

            else -> AppDiscoveryCompleteness.Partial
        }

        return AppDiscoveryReport(
            applications = merged.values.toList(),
            summary = AppDiscoverySummary(
                completeness = completeness,
                sources = buildList {
                    add(primary.report)
                    add(fallback.report)
                    addAll(supplementResults.map(SourceResult::report))
                },
            ),
        )
    }

    private fun execute(source: NamedAppDiscoverySource): SourceResult {
        return try {
            val applications = source.source.discover()
                .distinctBy(DiscoveredApplication::packageName)
            SourceResult(
                applications = applications,
                report = AppDiscoverySourceReport(
                    source = source.id,
                    outcome = AppDiscoverySourceOutcome.Success,
                    itemCount = applications.size,
                ),
            )
        } catch (failure: SecurityException) {
            failed(source.id, AppDiscoverySourceOutcome.PermissionDenied, failure)
        } catch (failure: IllegalStateException) {
            failed(source.id, AppDiscoverySourceOutcome.PlatformUnavailable, failure)
        } catch (failure: Exception) {
            failed(source.id, AppDiscoverySourceOutcome.UnexpectedFailure, failure)
        }
    }

    private fun failed(
        source: AppDiscoverySourceId,
        outcome: AppDiscoverySourceOutcome,
        failure: Exception,
    ): SourceResult = SourceResult(
        applications = emptyList(),
        report = AppDiscoverySourceReport(
            source = source,
            outcome = outcome,
            itemCount = 0,
            rawFailure = failure.toString(),
        ),
    )

    private data class SourceResult(
        val applications: List<DiscoveredApplication>,
        val report: AppDiscoverySourceReport,
    )
}

private fun List<DiscoveredApplication>.packageNames(): Set<String> =
    mapTo(linkedSetOf(), DiscoveredApplication::packageName)

private fun AppDiscoverySourceReport.rawText(): String =
    rawFailure?.let { "$source: $it" } ?: when (outcome) {
        AppDiscoverySourceOutcome.NotRequired -> "$source: $outcome"
        else -> "$source: $outcome, count=$itemCount"
    }
