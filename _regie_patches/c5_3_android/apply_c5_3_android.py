#!/usr/bin/env python3
from pathlib import Path
from datetime import datetime
import shutil

ROOT = Path.cwd()
PATCH_ROOT = Path(__file__).resolve().parent
REPLACEMENTS = PATCH_ROOT / 'replacements'
STAMP = datetime.now().strftime('%Y%m%d_%H%M%S')
BACKUP = ROOT / '_regie_backups' / f'c5_3_android_{STAMP}'
BACKUP.mkdir(parents=True, exist_ok=True)


def backup(path: Path):
    target = BACKUP / path.relative_to(ROOT)
    target.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and not target.exists():
        shutil.copy2(path, target)


def replace_once(file_name: str, old: str, new: str):
    path = ROOT / file_name
    if not path.exists():
        raise SystemExit(f'MISSING: {file_name}')
    data = path.read_text()
    if new in data:
        print(f'ALREADY: {file_name}')
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f'ANCHOR ERROR: {file_name}: expected 1 occurrence, got {count}')
    backup(path)
    path.write_text(data.replace(old, new, 1))
    print(f'PATCHED: {file_name}')


def replace_file(file_name: str):
    src = REPLACEMENTS / file_name
    dst = ROOT / file_name
    if not src.exists() or not dst.exists():
        raise SystemExit(f'MISSING replacement or target: {file_name}')
    new = src.read_text()
    old = dst.read_text()
    if old == new:
        print(f'ALREADY: {file_name}')
        return
    backup(dst)
    dst.write_text(new)
    print(f'REPLACED: {file_name}')

# Exact 0.14.9 C5.3 files audited from commit fe3ed3c.
replace_file('app/src/main/java/be/cookit/pos/android/data/fiscal/FiscalAgentRetryPolicy.kt')
replace_file('app/src/main/java/be/cookit/pos/android/data/fiscal/FiscalAgentClient.kt')
replace_file('app/src/main/java/be/cookit/pos/android/data/fiscal/FiscalAgentDiagnosticLogger.kt')

# Preserve existing positional FdmGraphqlException(responseBody) calls while adding typed HTTP status.
replace_once(
    'app/src/main/java/be/cookit/pos/android/data/fiscal/FdmGraphqlClient.kt',
    'class FdmGraphqlException(message: String, val responseBody: String = "") : Exception(message)',
    'class FdmGraphqlException(message: String, val responseBody: String = "", val httpStatus: Int? = null) : Exception(message)'
)
replace_once(
    'app/src/main/java/be/cookit/pos/android/data/fiscal/FdmGraphqlClient.kt',
    '                throw FdmGraphqlException("FDM GraphQL HTTP $code", text)',
    '                throw FdmGraphqlException("FDM GraphQL HTTP $code", text, httpStatus = code)'
)

service = 'app/src/main/java/be/cookit/pos/android/service/FiscalAgentForegroundService.kt'

# Send explicit terminal classification to Cloud.
replace_once(
    service,
    '''                                client.acknowledge(
                                    credentials = credentials,
                                    transactionId = failure.jobId,
                                    identity = identity,
                                    success = false,
                                    error = decision.errorForCloud
                                )''',
    '''                                client.acknowledge(
                                    credentials = credentials,
                                    transactionId = failure.jobId,
                                    identity = identity,
                                    success = false,
                                    error = decision.errorForCloud,
                                    failureClass = decision.failureClass.name,
                                    retryDisposition = decision.disposition.name
                                )'''
)

# RETRY_DECISION diagnostics: explicit classification + actual job attempt.
replace_once(
    service,
    '''                            diagnosticLogger.record(
                                eventType = FiscalAgentDiagnosticLogger.EVENT_RETRY_DECISION,
                                health = health,
                                jobId = failure.jobId,
                                jobPhase = FiscalAgentRuntimeStateStore.PHASE_TERMINALIZING,
                                provider = settings.provider,
                                runtimeId = identity.runtimeId,
                                message = decision.reasonCode,
                                errorClass = rootError::class.java.simpleName
                            )''',
    '''                            diagnosticLogger.record(
                                eventType = FiscalAgentDiagnosticLogger.EVENT_RETRY_DECISION,
                                health = health,
                                jobId = failure.jobId,
                                jobPhase = FiscalAgentRuntimeStateStore.PHASE_TERMINALIZING,
                                provider = settings.provider,
                                runtimeId = identity.runtimeId,
                                message = "${decision.failureClass.name}:${decision.reasonCode}",
                                errorClass = rootError::class.java.simpleName,
                                retryCount = failure.attempts
                            )'''
)

# Terminal failure journal keeps classification and actual attempt.
replace_once(
    service,
    '''                                    message = decision.errorForCloud,
                                    errorClass = rootError::class.java.simpleName
                                )''',
    '''                                    message = "${decision.failureClass.name}:${decision.reasonCode}",
                                    errorClass = rootError::class.java.simpleName,
                                    retryCount = failure.attempts
                                )'''
)

# Scheduled retry diagnostics: explicit class and actual attempt.
replace_once(
    service,
    '''                                message = decision.reasonCode,
                                errorClass = rootError::class.java.simpleName
                            )
                            refreshPendingOutcomeCount()
                            updateNotification("$health • Job #${failure.jobId} • $phase")''',
    '''                                message = "${decision.failureClass.name}:${decision.reasonCode}",
                                errorClass = rootError::class.java.simpleName,
                                retryCount = failure.attempts
                            )
                            refreshPendingOutcomeCount()
                            updateNotification("$health • Job #${failure.jobId} • $phase")'''
)

# Manual hold caused by provider retry exhaustion must emit RETRY_EXHAUSTED.
replace_once(
    service,
    '''                            diagnosticLogger.record(
                                eventType = FiscalAgentDiagnosticLogger.EVENT_MANUAL_HOLD,
                                health = FiscalAgentRuntimeState.HEALTH_DEGRADED,
                                jobId = failure.jobId,
                                jobPhase = FiscalAgentRuntimeStateStore.PHASE_MANUAL_HOLD,
                                provider = settings.provider,
                                runtimeId = identity.runtimeId,
                                message = decision.reasonCode,
                                errorClass = rootError::class.java.simpleName
                            )''',
    '''                            diagnosticLogger.record(
                                eventType = if (decision.reasonCode == "provider_retry_exhausted") {
                                    FiscalAgentDiagnosticLogger.EVENT_RETRY_EXHAUSTED
                                } else {
                                    FiscalAgentDiagnosticLogger.EVENT_MANUAL_HOLD
                                },
                                health = FiscalAgentRuntimeState.HEALTH_DEGRADED,
                                jobId = failure.jobId,
                                jobPhase = FiscalAgentRuntimeStateStore.PHASE_MANUAL_HOLD,
                                provider = settings.provider,
                                runtimeId = identity.runtimeId,
                                message = "${decision.failureClass.name}:${decision.reasonCode}",
                                errorClass = rootError::class.java.simpleName,
                                retryCount = failure.attempts
                            )'''
)

# Version bump for consolidation.
replace_once('app/build.gradle.kts', 'versionCode = 30', 'versionCode = 31')
replace_once('app/build.gradle.kts', 'versionName = "0.14.9"', 'versionName = "0.14.9.1"')

print('\nC5.3 ANDROID CONSOLIDATION COMPLETE')
print(f'Backup: {BACKUP.relative_to(ROOT)}')
