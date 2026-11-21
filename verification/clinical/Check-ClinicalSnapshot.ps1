#requires -Version 7.0
[CmdletBinding()]
param([string]$ExpectedPath, [string]$ActualPath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$issues = [System.Collections.Generic.List[object]]::new()
$script:inputIssue = $null
$dataKind = $null
$fixtureId = $null

function New-Index { return ,([System.Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)) }
function Same($Left, $Right) {
    return $Left -is [string] -and $Right -is [string] -and [string]::Equals($Left, $Right, [StringComparison]::Ordinal)
}
function Add-Issue([string]$Category, [string]$Field, $Ids, $Expected, $Actual) {
    $issues.Add([ordered]@{ category=$Category; field=$Field; correlation=$Ids; expected=$Expected; actual=$Actual })
}
function Invalid([string]$Field, [string]$Message, [string]$Category='INPUT_INVALID', $Ids=@{}) {
    $script:inputIssue = [ordered]@{ category=$Category; field=$Field; correlation=$Ids; message=$Message }
    throw [FormatException]::new($Message)
}
function Require-Object($Value, [string]$Field, [string[]]$Keys) {
    if ($Value -isnot [System.Collections.IDictionary]) { Invalid $Field 'Object required.' }
    foreach ($key in $Keys) {
        $found = $false
        # Method invocation enumerates real entries even when JSON has Keys/Count/GetEnumerator fields.
        foreach ($entry in $Value.GetEnumerator()) {
            if ([string]::Equals($entry.Key, $key, [StringComparison]::Ordinal)) { $found = $true; break }
        }
        if (-not $found) { Invalid "$Field.$key" 'Required field missing (case sensitive).' }
    }
}
function Require-Enum($Value, [string]$Field, [string[]]$Allowed, $Ids=@{}) {
    if ($Value -isnot [string]) { Invalid $Field 'JSON string scalar required.' 'INPUT_INVALID' $Ids }
    foreach ($candidate in $Allowed) {
        if (Same $Value $candidate) { return }
    }
    Invalid $Field 'Unsupported string value (case sensitive).' 'INPUT_INVALID' $Ids
}
function Require-Id($Value, [string]$Field) {
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value) -or $Value -cne $Value.Trim()) {
        Invalid $Field 'Nonblank unpadded string required.'
    }
}
function Require-Array($Value, [string]$Field) {
    if ($Value -isnot [array]) { Invalid $Field 'JSON array required, including when empty.' }
}
function Require-Payload($Value, [string]$Field) {
    Require-Object $Value $Field @('procedureCode','batchId','doseOrdinal')
    Require-Id $Value.procedureCode "$Field.procedureCode"
    Require-Id $Value.batchId "$Field.batchId"
    if (($Value.doseOrdinal -isnot [long] -and $Value.doseOrdinal -isnot [int]) -or $Value.doseOrdinal -lt 1) {
        Invalid "$Field.doseOrdinal" 'Positive integer required by this verification projection.'
    }
}
function Check-JsonKeys([System.Text.Json.JsonElement]$Element, [string]$Field) {
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $keys = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $Element.EnumerateObject()) {
            if (-not $keys.Add($property.Name)) { Invalid $Field 'Duplicate JSON property.' }
            Check-JsonKeys $property.Value "$Field.$($property.Name)"
        }
    } elseif ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        $i=0
        foreach ($item in $Element.EnumerateArray()) { Check-JsonKeys $item "$Field[$i]"; $i++ }
    }
}
function Read-Input([string]$Path, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Invalid $Label 'Input file required and must exist.'
    }
    $document = $null
    try {
        $raw = [IO.File]::ReadAllText((Get-Item -LiteralPath $Path).FullName)
        $options = [System.Text.Json.JsonDocumentOptions]::new()
        $options.MaxDepth = 32
        $document = [System.Text.Json.JsonDocument]::Parse($raw, $options)
        Check-JsonKeys $document.RootElement $Label
        return ,(ConvertFrom-Json -InputObject $raw -AsHashtable -Depth 32 -NoEnumerate)
    } catch {
        if ($null -ne $script:inputIssue) { throw }
        Invalid $Label 'Unreadable/invalid JSON (strict JSON, maximum depth 32).'
    } finally {
        if ($null -ne $document) { $document.Dispose() }
    }
}
function Add-Unique($Index, [string]$Id, $Value, [string]$Category, [string]$Field, $Ids) {
    if ($Index.ContainsKey($Id)) { Invalid $Field 'Duplicate identity; input cannot be reconciled safely.' $Category $Ids }
    $Index.Add($Id, $Value)
}
function Compare-Content($Expected, $Actual, $Ids) {
    foreach ($field in @('actorId','cattleId','originFarmId')) {
        if (-not (Same $Expected[$field] $Actual[$field])) {
            Add-Issue 'CONTENT_MISMATCH' $field $Ids $Expected[$field] $Actual[$field]
        }
    }
    foreach ($field in @('procedureCode','batchId','doseOrdinal')) {
        if ($field -ceq 'doseOrdinal') { $equal = $Expected.payload[$field] -eq $Actual.payload[$field] }
        else { $equal = Same $Expected.payload[$field] $Actual.payload[$field] }
        if (-not $equal) { Add-Issue 'CONTENT_MISMATCH' "payload.$field" $Ids $Expected.payload[$field] $Actual.payload[$field] }
    }
}

try {
    $expected = Read-Input $ExpectedPath 'expected'
    $actual = Read-Input $ActualPath 'actual'
    Require-Object $expected 'expected' @('formatVersion','kind','fixtureId','intents','attempts')
    Require-Object $actual 'actual' @('formatVersion','kind','fixtureId','dataKind','effects','sourceEvidence','outcomes')
    Require-Enum $expected.formatVersion 'expected.formatVersion' @('clinical-verification/1')
    Require-Enum $actual.formatVersion 'actual.formatVersion' @('clinical-verification/1')
    Require-Enum $expected.kind 'expected.kind' @('expected')
    Require-Enum $actual.kind 'actual.kind' @('actual')
    Require-Enum $actual.dataKind 'actual.dataKind' @('SYNTHETIC','RUNTIME_EXPORT')
    Require-Id $expected.fixtureId 'expected.fixtureId'
    Require-Id $actual.fixtureId 'actual.fixtureId'
    if (-not (Same $expected.fixtureId $actual.fixtureId)) { Invalid 'fixtureId' 'Snapshots belong to different fixtures.' }
    $dataKind = $actual.dataKind
    $fixtureId = $expected.fixtureId
    foreach ($name in @('intents','attempts')) { Require-Array $expected[$name] "expected.$name" }
    foreach ($name in @('effects','sourceEvidence','outcomes')) { Require-Array $actual[$name] "actual.$name" }
    if ($expected.intents.Count -eq 0 -or $expected.attempts.Count -eq 0) { Invalid 'expected' 'Nonempty intents and attempts required; empty oracle must not pass vacuously.' }

    $intents = New-Index
    $attempts = New-Index
    $effects = New-Index
    $evidence = New-Index
    $observations = New-Index
    foreach ($row in $expected.intents) {
        Require-Object $row 'expected.intents[]' @('intentId','actorId','cattleId','originFarmId','payload','expectedEffectCount','requiredEvidenceKinds')
        foreach ($field in @('intentId','actorId','cattleId','originFarmId')) { Require-Id $row[$field] "expected.intents[].$field" }
        Require-Payload $row.payload 'expected.intents[].payload'
        if (($row.expectedEffectCount -isnot [long] -and $row.expectedEffectCount -isnot [int]) -or
            @(0,1) -notcontains $row.expectedEffectCount) { Invalid 'expectedEffectCount' 'This small checker supports exactly zero or one durable effect per intent.' }
        Require-Array $row.requiredEvidenceKinds 'requiredEvidenceKinds'
        $kinds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($kind in $row.requiredEvidenceKinds) {
            Require-Id $kind 'requiredEvidenceKinds[]'
            if (-not $kinds.Add($kind)) { Invalid 'requiredEvidenceKinds' 'Duplicate required evidence kind.' }
        }
        if (($row.expectedEffectCount -eq 1 -and $kinds.Count -eq 0) -or
            ($row.expectedEffectCount -eq 0 -and $kinds.Count -ne 0)) {
            Invalid 'requiredEvidenceKinds' 'One-effect oracle requires evidence kinds; zero-effect oracle must have none.'
        }
        Add-Unique $intents $row.intentId $row 'DUPLICATE_EXPECTED_ID' 'expected.intents[].intentId' @{intentId=$row.intentId}
    }
    foreach ($row in $expected.attempts) {
        Require-Object $row 'expected.attempts[]' @('attemptId','intentId','expectedOutcome','request')
        Require-Id $row.attemptId 'attemptId'
        Require-Id $row.intentId 'intentId'
        if (-not $intents.ContainsKey($row.intentId)) { Invalid 'expected.attempts[].intentId' 'Expected attempt must link an expected intent.' }
        Require-Enum $row.expectedOutcome 'expected.attempts[].expectedOutcome' @('ACCEPTED','DENIED','CONFLICT') @{intentId=$row.intentId;attemptId=$row.attemptId}
        Require-Object $row.request 'request' @('actorId','cattleId','payload')
        Require-Id $row.request.actorId 'request.actorId'
        Require-Id $row.request.cattleId 'request.cattleId'
        Require-Payload $row.request.payload 'request.payload'
        $intent = $intents[$row.intentId]
        if ((Same $row.expectedOutcome 'ACCEPTED') -and ($intent.expectedEffectCount -ne 1 -or
            -not (Same $row.request.actorId $intent.actorId) -or -not (Same $row.request.cattleId $intent.cattleId) -or
            -not (Same $row.request.payload.procedureCode $intent.payload.procedureCode) -or
            -not (Same $row.request.payload.batchId $intent.payload.batchId) -or
            $row.request.payload.doseOrdinal -ne $intent.payload.doseOrdinal)) {
            Invalid 'expected.attempts[].request' 'Accepted expected attempt must agree with its immutable one-effect oracle.'
        }
        Add-Unique $attempts $row.attemptId $row 'DUPLICATE_EXPECTED_ATTEMPT_ID' 'attemptId' @{intentId=$row.intentId;attemptId=$row.attemptId}
    }
    foreach ($row in $expected.intents) {
        $related = @($expected.attempts | Where-Object { Same $_.intentId $row.intentId })
        if ($related.Count -eq 0 -or ($row.expectedEffectCount -eq 1 -and @($related | Where-Object { Same $_.expectedOutcome 'ACCEPTED' }).Count -eq 0)) {
            Invalid 'expected.intents' 'Each intent needs attempts; each expected effect needs an accepted attempt.'
        }
    }
    foreach ($row in $actual.effects) {
        Require-Object $row 'actual.effects[]' @('effectId','intentId','actorId','cattleId','originFarmId','payload')
        foreach ($field in @('effectId','intentId','actorId','cattleId','originFarmId')) { Require-Id $row[$field] "actual.effects[].$field" }
        Require-Payload $row.payload 'actual.effects[].payload'
        Add-Unique $effects $row.effectId $row 'DUPLICATE_EFFECT_ID' 'effectId' @{intentId=$row.intentId;effectId=$row.effectId}
    }
    foreach ($row in $actual.sourceEvidence) {
        Require-Object $row 'actual.sourceEvidence[]' @('evidenceId','effectId','intentId','kind')
        foreach ($field in @('evidenceId','effectId','intentId','kind')) { Require-Id $row[$field] "actual.sourceEvidence[].$field" }
        Add-Unique $evidence $row.evidenceId $row 'DUPLICATE_EVIDENCE_ID' 'evidenceId' @{intentId=$row.intentId;effectId=$row.effectId;evidenceId=$row.evidenceId}
    }
    foreach ($row in $actual.outcomes) {
        Require-Object $row 'actual.outcomes[]' @('observationId','attemptId','intentId','outcome','effectId')
        foreach ($field in @('observationId','attemptId','intentId')) { Require-Id $row[$field] "actual.outcomes[].$field" }
        Require-Enum $row.outcome 'actual.outcomes[].outcome' @('ACCEPTED','DENIED','CONFLICT') @{intentId=$row.intentId;attemptId=$row.attemptId;observationId=$row.observationId}
        if ($null -ne $row.effectId) { Require-Id $row.effectId 'actual.outcomes[].effectId' }
        Add-Unique $observations $row.observationId $row 'DUPLICATE_OBSERVATION_ID' 'observationId' @{intentId=$row.intentId;attemptId=$row.attemptId;observationId=$row.observationId}
    }

    # Only durable effect rows determine business cardinality, never response counts.
    foreach ($row in $actual.effects) {
        $ids = @{intentId=$row.intentId;effectId=$row.effectId}
        if (-not $intents.ContainsKey($row.intentId)) { Add-Issue 'UNEXPECTED_EFFECT' 'intentId' $ids 'expected intent' $row.intentId; continue }
        Compare-Content $intents[$row.intentId] $row $ids
    }
    foreach ($row in $expected.intents) {
        $found = @($actual.effects | Where-Object { Same $_.intentId $row.intentId })
        $ids = @{intentId=$row.intentId}
        if ($found.Count -lt $row.expectedEffectCount) { Add-Issue 'MISSING_EFFECT' 'effectCount' $ids $row.expectedEffectCount $found.Count }
        if ($found.Count -gt $row.expectedEffectCount) {
            $category = if ($row.expectedEffectCount -eq 0) { 'UNEXPECTED_EFFECT' } else { 'DUPLICATE_CLINICAL_EFFECT' }
            Add-Issue $category 'effectCount' $ids $row.expectedEffectCount $found.Count
        }
        foreach ($effect in $found) {
            foreach ($kind in $row.requiredEvidenceKinds) {
                $linked = @($actual.sourceEvidence | Where-Object {
                    (Same $_.effectId $effect.effectId) -and (Same $_.intentId $row.intentId) -and (Same $_.kind $kind)
                })
                if ($linked.Count -eq 0) {
                    Add-Issue 'MISSING_SOURCE_EVIDENCE' 'kind' @{intentId=$row.intentId;effectId=$effect.effectId} $kind $null
                }
            }
        }
    }
    foreach ($row in $actual.sourceEvidence) {
        $ids = @{intentId=$row.intentId;effectId=$row.effectId;evidenceId=$row.evidenceId}
        if (-not $effects.ContainsKey($row.effectId)) { Add-Issue 'ORPHAN_SOURCE_EVIDENCE' 'effectId' $ids 'observed effect' $row.effectId }
        elseif (-not (Same $effects[$row.effectId].intentId $row.intentId)) {
            Add-Issue 'EVIDENCE_LINK_MISMATCH' 'intentId' $ids $effects[$row.effectId].intentId $row.intentId
        }
    }
    foreach ($row in $expected.attempts) {
        $found = @($actual.outcomes | Where-Object { Same $_.attemptId $row.attemptId })
        if ($found.Count -eq 0) { Add-Issue 'MISSING_OUTCOME' 'attemptId' @{intentId=$row.intentId;attemptId=$row.attemptId} 'one or more observations' 0 }
    }
    foreach ($row in $actual.outcomes) {
        $ids = @{intentId=$row.intentId;attemptId=$row.attemptId;observationId=$row.observationId;effectId=$row.effectId}
        if (-not $attempts.ContainsKey($row.attemptId)) { Add-Issue 'UNEXPECTED_OUTCOME' 'attemptId' $ids 'expected attempt' $row.attemptId; continue }
        $attempt = $attempts[$row.attemptId]
        if (-not (Same $attempt.intentId $row.intentId)) { Add-Issue 'OUTCOME_INTENT_MISMATCH' 'intentId' $ids $attempt.intentId $row.intentId }
        if (-not (Same $attempt.expectedOutcome $row.outcome)) { Add-Issue 'OUTCOME_MISMATCH' 'outcome' $ids $attempt.expectedOutcome $row.outcome }
        if (Same $row.outcome 'ACCEPTED') {
            if ($null -eq $row.effectId -or -not $effects.ContainsKey($row.effectId)) {
                Add-Issue 'OUTCOME_EFFECT_MISMATCH' 'effectId' $ids 'linked observed effect for accepted outcome' $row.effectId
            } elseif (-not (Same $effects[$row.effectId].intentId $row.intentId)) {
                Add-Issue 'OUTCOME_EFFECT_MISMATCH' 'effectId' $ids 'effect of the same intent' $row.effectId
            }
        } elseif ($null -ne $row.effectId) { Add-Issue 'OUTCOME_EFFECT_MISMATCH' 'effectId' $ids $null $row.effectId }
    }
    $exitCode = if ($issues.Count -eq 0) { 0 } else { 1 }
    $report = [ordered]@{
        formatVersion='clinical-check-result/1'; status=$(if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' })
        fixtureId=$fixtureId; dataKind=$dataKind
        counts=[ordered]@{ expectedIntents=$intents.Count; expectedAttempts=$attempts.Count; actualEffects=$effects.Count;
                          sourceEvidence=$evidence.Count; outcomeObservations=$observations.Count; diagnostics=$issues.Count }
        diagnostics=@($issues.ToArray())
    }
} catch {
    $exitCode = if ($null -ne $script:inputIssue) { 2 } else { 3 }
    $diagnostic = if ($null -ne $script:inputIssue) { $script:inputIssue } else {
        [ordered]@{ category='CHECKER_ERROR'; field='checker'; correlation=@{}; message='Unexpected checker failure; no successful reconciliation was established.' }
    }
    $report = [ordered]@{ formatVersion='clinical-check-result/1'; status=$(if ($exitCode -eq 2) { 'INVALID' } else { 'ERROR' });
                         fixtureId=$fixtureId; dataKind=$dataKind; diagnostics=@($diagnostic) }
}
[Console]::Out.WriteLine(($report | ConvertTo-Json -Depth 12 -Compress))
exit $exitCode
