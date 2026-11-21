#requires -Version 7.0
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$checker = Join-Path $PSScriptRoot 'Check-ClinicalSnapshot.ps1'
$expectedPath = Join-Path $PSScriptRoot 'fixtures/expected.small.json'
$actualPath = Join-Path $PSScriptRoot 'fixtures/actual.synthetic.json'
$pwsh = (Get-Command pwsh -ErrorAction Stop).Source
$before = @{}
foreach ($path in @($checker,$expectedPath,$actualPath)) { $before[$path] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
$baseExpected = Get-Content -LiteralPath $expectedPath -Raw
$baseActual = Get-Content -LiteralPath $actualPath -Raw
$temp = Join-Path ([IO.Path]::GetTempPath()) ('vinabeef-clinical-checker-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $temp
$created = [System.Collections.Generic.List[string]]::new()
$results = [System.Collections.Generic.List[object]]::new()

function Control([string]$Name, [int]$ExitCode, [string]$Category, $Correlation, [scriptblock]$Mutate,
                 [string]$Field='', [string]$InputMode='json', [string]$Group='baseline') {
    return @{name=$Name; exitCode=$ExitCode; category=$Category; correlation=$Correlation; mutate=$Mutate; field=$Field; inputMode=$InputMode; group=$Group}
}
function Clone($Value) { return ,($Value | ConvertTo-Json -Depth 20 | ConvertFrom-Json -AsHashtable -Depth 20 -NoEnumerate) }

$controls = @(
    (Control 'positive_retries_and_repeated_receipt' 0 '' @{} { param($e,$a) }),
    (Control 'positive_order_and_extra_system_fields' 0 '' @{} {
        param($e,$a)
        $a.effects[0].payload = [ordered]@{doseOrdinal=1; batchId='LOT-DEMO-A'; serverNote='ignored field'; procedureCode='PROC-DEMO-A'}
        $a.effects[0].serverAssignedAt = 'not-compared-without-contract'
        [array]::Reverse($a.effects); [array]::Reverse($a.sourceEvidence); [array]::Reverse($a.outcomes)
    }),
    (Control 'missing_accepted_effect_despite_claimed_acceptance' 1 'MISSING_EFFECT' @{intentId='I-01'} {
        param($e,$a)
        $a.effects = @($a.effects | Where-Object intentId -CNE 'I-01')
        $a.sourceEvidence = @($a.sourceEvidence | Where-Object intentId -CNE 'I-01')
    }),
    (Control 'two_durable_effects_different_ids_same_intent' 1 'DUPLICATE_CLINICAL_EFFECT' @{intentId='I-01'} {
        param($e,$a)
        $extra = Clone $a.effects[0]; $extra.effectId = 'E-103'; $a.effects += $extra
        $a.sourceEvidence += @{evidenceId='S-103';effectId='E-103';intentId='I-01';kind='CLINICAL_ACCEPTED'}
    }),
    (Control 'duplicate_physical_effect_row_same_id' 2 'DUPLICATE_EFFECT_ID' @{intentId='I-01';effectId='E-101'} {
        param($e,$a) $a.effects += (Clone $a.effects[0])
    }),
    (Control 'wrong_cattle_same_total_rows' 1 'CONTENT_MISMATCH' @{intentId='I-01';effectId='E-101'} {
        param($e,$a) $a.effects[0].cattleId = 'CB'
    } 'cattleId'),
    (Control 'wrong_actor_same_total_rows' 1 'CONTENT_MISMATCH' @{intentId='I-01';effectId='E-101'} {
        param($e,$a) $a.effects[0].actorId = 'FM-A'
    } 'actorId'),
    (Control 'wrong_origin_same_total_rows' 1 'CONTENT_MISMATCH' @{intentId='I-01';effectId='E-101'} {
        param($e,$a) $a.effects[0].originFarmId = 'FB'
    } 'originFarmId'),
    (Control 'changed_batch_would_mutate_original_intent' 1 'CONTENT_MISMATCH' @{intentId='I-01';effectId='E-101'} {
        param($e,$a) $a.effects[0].payload.batchId = 'LOT-CHANGED'
    } 'payload.batchId'),
    (Control 'wrong_procedure_same_total_rows' 1 'CONTENT_MISMATCH' @{intentId='I-02';effectId='E-102'} {
        param($e,$a) $a.effects[1].payload.procedureCode = 'PROC-WRONG'
    } 'payload.procedureCode'),
    (Control 'wrong_dose_ordinal_same_total_rows' 1 'CONTENT_MISMATCH' @{intentId='I-02';effectId='E-102'} {
        param($e,$a) $a.effects[1].payload.doseOrdinal = 2
    } 'payload.doseOrdinal'),
    (Control 'unexpected_intent_outside_oracle' 1 'UNEXPECTED_EFFECT' @{intentId='I-UNEXPECTED';effectId='E-X'} {
        param($e,$a)
        $extra = Clone $a.effects[1]; $extra.effectId='E-X'; $extra.intentId='I-UNEXPECTED'; $a.effects += $extra
        $a.sourceEvidence += @{evidenceId='S-X';effectId='E-X';intentId='I-UNEXPECTED';kind='CLINICAL_ACCEPTED'}
    }),
    (Control 'denied_intent_must_have_zero_effects' 1 'UNEXPECTED_EFFECT' @{intentId='I-03'} {
        param($e,$a)
        $extra=Clone $e.intents[2]; $extra.effectId='E-DENIED'; $a.effects += $extra
    }),
    (Control 'new_conflict_intent_must_have_zero_effects' 1 'UNEXPECTED_EFFECT' @{intentId='I-04'} {
        param($e,$a)
        $extra=Clone $e.intents[3]; $extra.effectId='E-CONFLICT'; $a.effects += $extra
    }),
    (Control 'missing_required_source_evidence' 1 'MISSING_SOURCE_EVIDENCE' @{intentId='I-01';effectId='E-101'} {
        param($e,$a) $a.sourceEvidence = @($a.sourceEvidence | Where-Object effectId -CNE 'E-101')
    }),
    (Control 'evidence_linked_to_wrong_intent_is_not_enough' 1 'EVIDENCE_LINK_MISMATCH' @{intentId='I-02';effectId='E-101';evidenceId='S-101'} {
        param($e,$a) $a.sourceEvidence[0].intentId='I-02'
    }),
    (Control 'orphan_source_evidence' 1 'ORPHAN_SOURCE_EVIDENCE' @{intentId='I-01';effectId='E-NOT-OBSERVED';evidenceId='S-101'} {
        param($e,$a) $a.sourceEvidence[0].effectId='E-NOT-OBSERVED'
    }),
    (Control 'unrelated_evidence_kind_cannot_satisfy_required_kind' 1 'MISSING_SOURCE_EVIDENCE' @{intentId='I-01';effectId='E-101'} {
        param($e,$a) $a.sourceEvidence[0].kind='UNRELATED_AUDIT'
    }),
    (Control 'outcome_claim_cannot_replace_expected_denial' 1 'OUTCOME_MISMATCH' @{intentId='I-03';attemptId='A-05'} {
        param($e,$a) $a.outcomes[5].outcome='ACCEPTED'; $a.outcomes[5].effectId='E-101'
    }),
    (Control 'accepted_outcome_wrong_effect_link' 1 'OUTCOME_EFFECT_MISMATCH' @{intentId='I-02';attemptId='A-04'} {
        param($e,$a) $a.outcomes[4].effectId='E-101'
    }),
    (Control 'missing_outcome_observation' 1 'MISSING_OUTCOME' @{intentId='I-04';attemptId='A-06'} {
        param($e,$a) $a.outcomes = @($a.outcomes | Where-Object attemptId -CNE 'A-06')
    }),
    (Control 'outcome_wrong_intent_link' 1 'OUTCOME_INTENT_MISMATCH' @{intentId='I-02';attemptId='A-01'} {
        param($e,$a) $a.outcomes[0].intentId='I-02'
    }),
    (Control 'identifiers_are_case_sensitive' 1 'CONTENT_MISMATCH' @{intentId='I-01';effectId='E-101'} {
        param($e,$a) $a.effects[0].cattleId='ca'
    } 'cattleId'),
    (Control 'duplicate_expected_intent_key_not_last_wins' 2 'DUPLICATE_EXPECTED_ID' @{intentId='I-01'} {
        param($e,$a) $e.intents += (Clone $e.intents[0])
    }),
    (Control 'duplicate_expected_attempt_key' 2 'DUPLICATE_EXPECTED_ATTEMPT_ID' @{intentId='I-01';attemptId='A-01'} {
        param($e,$a) $e.attempts += (Clone $e.attempts[0])
    }),
    (Control 'duplicate_evidence_identity' 2 'DUPLICATE_EVIDENCE_ID' @{intentId='I-01';effectId='E-101';evidenceId='S-101'} {
        param($e,$a) $a.sourceEvidence += (Clone $a.sourceEvidence[0])
    }),
    (Control 'duplicate_observation_identity' 2 'DUPLICATE_OBSERVATION_ID' @{intentId='I-01';attemptId='A-01';observationId='O-01'} {
        param($e,$a) $a.outcomes += (Clone $a.outcomes[0])
    }),
    (Control 'missing_required_content' 2 'INPUT_INVALID' @{} {
        param($e,$a) $a.effects[0].Remove('cattleId')
    } 'actual.effects[].cattleId'),
    (Control 'malformed_payload_type' 2 'INPUT_INVALID' @{} {
        param($e,$a) $a.effects[0].payload.doseOrdinal='1'
    } 'actual.effects[].payload.doseOrdinal'),
    (Control 'empty_oracle_cannot_pass_vacuously' 2 'INPUT_INVALID' @{} {
        param($e,$a) $e.intents=@(); $e.attempts=@()
    } 'expected'),
    (Control 'invalid_json' 2 'INPUT_INVALID' @{} {param($e,$a)} 'actual' 'invalid-json'),
    (Control 'duplicate_json_property' 2 'INPUT_INVALID' @{} {param($e,$a)} 'actual' 'duplicate-property'),
    (Control 'missing_actual_file' 2 'INPUT_INVALID' @{} {param($e,$a)} 'actual' 'missing-actual'),
    (Control 'missing_expected_file' 2 'INPUT_INVALID' @{} {param($e,$a)} 'expected' 'missing-expected')
)

# QA-3A-01: exercise every external enum/string field without relying on PS coercion.
$scalarTargets = @(
    @{name='expected_formatVersion'; field='expected.formatVersion'; valid='clinical-verification/1'; wrongCase='CLINICAL-VERIFICATION/1'; ids=@{}; set={param($e,$a,$v) $e['formatVersion']=$v}},
    @{name='actual_formatVersion'; field='actual.formatVersion'; valid='clinical-verification/1'; wrongCase='CLINICAL-VERIFICATION/1'; ids=@{}; set={param($e,$a,$v) $a['formatVersion']=$v}},
    @{name='expected_kind'; field='expected.kind'; valid='expected'; wrongCase='EXPECTED'; ids=@{}; set={param($e,$a,$v) $e['kind']=$v}},
    @{name='actual_kind'; field='actual.kind'; valid='actual'; wrongCase='ACTUAL'; ids=@{}; set={param($e,$a,$v) $a['kind']=$v}},
    @{name='actual_dataKind'; field='actual.dataKind'; valid='SYNTHETIC'; wrongCase='synthetic'; ids=@{}; set={param($e,$a,$v) $a['dataKind']=$v}},
    @{name='expected_expectedOutcome'; field='expected.attempts[].expectedOutcome'; valid='ACCEPTED'; wrongCase='accepted'; ids=@{intentId='I-01';attemptId='A-01'}; set={param($e,$a,$v) $e.attempts[0]['expectedOutcome']=$v}},
    @{name='actual_outcome'; field='actual.outcomes[].outcome'; valid='ACCEPTED'; wrongCase='accepted'; ids=@{intentId='I-01';attemptId='A-01';observationId='O-01'}; set={param($e,$a,$v) $a.outcomes[0]['outcome']=$v}}
)
foreach ($target in $scalarTargets) {
    $invalidScalars = @(
        @{name='singleton_array'; value=@($target.valid)},
        @{name='number'; value=7},
        @{name='boolean'; value=$true},
        @{name='object'; value=@{value=$target.valid}},
        @{name='null'; value=$null}
    )
    foreach ($invalidScalar in $invalidScalars) {
        $setter=$target.set
        $invalidValue=$invalidScalar.value
        $mutation={param($e,$a) & $setter $e $a $invalidValue}.GetNewClosure()
        $controls += Control ("r2_$($target.name)_$($invalidScalar.name)") 2 'INPUT_INVALID' $target.ids $mutation -Field $target.field -Group 'QA-3A-01'
    }
    $setter=$target.set
    $invalidValue=$target.wrongCase
    $mutation={param($e,$a) & $setter $e $a $invalidValue}.GetNewClosure()
    $controls += Control ("r2_$($target.name)_wrong_case") 2 'INPUT_INVALID' $target.ids $mutation -Field $target.field -Group 'QA-3A-01'
}

# QA-3A-02: unknown data names cannot replace or hide dictionary member APIs.
$controls += @(
    (Control 'r2_root_member_collisions_are_ignored' 0 '' @{} {
        param($e,$a)
        foreach ($object in @($e,$a)) {
            $object['Keys']=@('unexpected-system-field'); $object['Count']=0
            $object['Values']='ignored'; $object['PSBase']='ignored'; $object['GetEnumerator']='ignored'
            $object['get_Keys']='ignored'; $object['ContainsKey']='ignored'; $object['Item']='ignored'
        }
    } -Group 'QA-3A-02'),
    (Control 'r2_nested_member_collisions_are_ignored' 0 '' @{} {
        param($e,$a)
        $objects=@($e.intents[0],$e.intents[0].payload,$e.attempts[0],$e.attempts[0].request,
                   $e.attempts[0].request.payload,$a.effects[0],$a.effects[0].payload,$a.sourceEvidence[0],$a.outcomes[0])
        foreach ($object in $objects) {
            $object['Keys']=@('unexpected-system-field'); $object['Count']=0
            $object['Values']='ignored'; $object['PSBase']=@{ignored=$true}; $object['GetEnumerator']='ignored'
            $object['get_Keys']='ignored'; $object['ContainsKey']='ignored'; $object['Item']='ignored'
        }
    } -Group 'QA-3A-02'),
    (Control 'r2_unknown_case_variant_field_is_ignored' 0 '' @{} {
        param($e,$a) $a.effects[0]['ActorId']='IGNORED-SYSTEM-FIELD'
    } -Group 'QA-3A-02'),
    (Control 'r2_missing_required_root_despite_spoofed_Keys' 2 'INPUT_INVALID' @{} {
        param($e,$a)
        $a.Remove('formatVersion')
        $a['Keys']=@('formatVersion','kind','fixtureId','dataKind','effects','sourceEvidence','outcomes')
    } -Field 'actual.formatVersion' -Group 'QA-3A-02'),
    (Control 'r2_required_root_key_wrong_case' 2 'INPUT_INVALID' @{} {
        param($e,$a)
        $e['FormatVersion']=$e['formatVersion']; $e.Remove('formatVersion')
    } -Field 'expected.formatVersion' -Group 'QA-3A-02'),
    (Control 'r2_required_nested_key_wrong_case' 2 'INPUT_INVALID' @{} {
        param($e,$a)
        $a.effects[0]['CattleId']=$a.effects[0]['cattleId']; $a.effects[0].Remove('cattleId')
        $a.effects[0]['Keys']=@('cattleId')
    } -Field 'actual.effects[].cattleId' -Group 'QA-3A-02')
)

try {
    foreach ($control in $controls) {
        $e = ConvertFrom-Json -InputObject $baseExpected -AsHashtable -Depth 20 -NoEnumerate
        $a = ConvertFrom-Json -InputObject $baseActual -AsHashtable -Depth 20 -NoEnumerate
        $null = & $control.mutate $e $a
        $ePath = Join-Path $temp ($control.name + '.expected.json')
        $aPath = Join-Path $temp ($control.name + '.actual.json')
        $e | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ePath -Encoding utf8
        $a | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $aPath -Encoding utf8
        $created.Add($ePath); $created.Add($aPath)
        switch ($control.inputMode) {
            'invalid-json' { [IO.File]::WriteAllText($aPath, '{"formatVersion":') }
            'duplicate-property' { [IO.File]::WriteAllText($aPath, '{"kind":"actual","kind":"actual"}') }
            'missing-actual' { $aPath=Join-Path $temp 'does-not-exist.actual.json' }
            'missing-expected' { $ePath=Join-Path $temp 'does-not-exist.expected.json' }
        }
        $variantHashes = @{}
        foreach ($p in @($ePath,$aPath)) {
            if (Test-Path -LiteralPath $p -PathType Leaf) { $variantHashes[$p]=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash }
        }
        $raw = (& $pwsh -NoLogo -NoProfile -File $checker -ExpectedPath $ePath -ActualPath $aPath) -join "`n"
        $actualExit=$LASTEXITCODE
        $result = ConvertFrom-Json -InputObject $raw -AsHashtable -Depth 20
        $readOnly=$true
        foreach ($p in $variantHashes.Keys) {
            if ((Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash -cne $variantHashes[$p]) { $readOnly=$false }
        }
        $matched = @($result.diagnostics | Where-Object {
            $diagnostic=$_
            $ok=$diagnostic.category -ceq $control.category
            if ($control.field) { $ok=$ok -and $diagnostic.field -ceq $control.field }
            foreach ($key in $control.correlation.Keys) {
                $ok=$ok -and ($diagnostic.correlation.Keys -ccontains $key) -and
                    $diagnostic.correlation[$key] -ceq $control.correlation[$key]
            }
            $ok
        })
        $expectedStatus = switch ($control.exitCode) {0 {'PASS'} 1 {'FAIL'} 2 {'INVALID'}}
        $pass = $actualExit -eq $control.exitCode -and $result.status -ceq $expectedStatus -and $readOnly
        if ($control.exitCode -eq 0) { $pass=$pass -and $result.diagnostics.Count -eq 0 }
        else { $pass=$pass -and $matched.Count -gt 0 }
        $results.Add([ordered]@{
            name=$control.name; group=$control.group; passed=$pass; expectedExit=$control.exitCode; actualExit=$actualExit
            expectedDiagnostic=[ordered]@{category=$control.category;correlation=$control.correlation;field=$control.field}
            variantInputsUnchanged=$readOnly; actualResult=$result
        })
    }
    $canonicalUnchanged=$true
    foreach ($path in $before.Keys) {
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $before[$path]) { $canonicalUnchanged=$false }
    }
    $failed = @($results | Where-Object { -not $_.passed }).Count
    $pass = $failed -eq 0 -and $canonicalUnchanged
    $report=[ordered]@{
        formatVersion='clinical-checker-self-test/1'; dataKind='SYNTHETIC'; status=$(if ($pass) {'PASS'} else {'FAIL'})
        powershellVersion=$PSVersionTable.PSVersion.ToString(); controls=$results.Count
        passed=@($results | Where-Object passed).Count; failed=$failed; canonicalInputsUnchanged=$canonicalUnchanged
        results=@($results.ToArray())
    }
    [Console]::Out.WriteLine(($report | ConvertTo-Json -Depth 20))
    if ($pass) { exit 0 } else { exit 1 }
} finally {
    # Remove only this run's explicitly recorded temporary files and now-empty directory.
    foreach ($path in $created) { Remove-Item -LiteralPath $path -Force }
    Remove-Item -LiteralPath $temp
}
