$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$splitRoot = Join-Path -Path $repoRoot -ChildPath "sql/production-split"
$manifestFile = Join-Path -Path $splitRoot -ChildPath "manifest.json"

if (-not (Test-Path -LiteralPath $manifestFile)) {
    Write-Host "FAIL missing production split manifest: $manifestFile"
    exit 1
}

$manifest = Get-Content -Raw -LiteralPath $manifestFile | ConvertFrom-Json
$expectedKeys = @("user", "ticket", "order", "payment", "notification", "grab")

function New-ColumnSet([string[]] $columns) {
    $set = @{}
    foreach ($column in $columns) {
        $set[$column.ToLower()] = $true
    }
    return $set
}

function Normalize-Identifier([string] $identifier) {
    $value = $identifier.Trim()
    if ($value.Contains('.')) {
        $value = ($value -split '\.')[-1]
    }
    return $value.Trim('"').ToLower()
}

function Get-IdentifierFromMatch($match, [int] $quotedGroup, [int] $bareGroup) {
    $value = $match.Groups[$quotedGroup].Value
    if (-not $value) {
        $value = $match.Groups[$bareGroup].Value
    }
    return (Normalize-Identifier $value)
}

function Get-ColumnList([string] $columns) {
    $result = @()
    foreach ($column in ($columns -split ',')) {
        $result += (Normalize-Identifier $column)
    }
    return $result
}

function Test-SqlStatementTargetOwner($match, [string] $filePath, [string] $serviceKey, [int] $quotedGroup, [int] $bareGroup, [string] $operation) {
    $table = Get-IdentifierFromMatch $match $quotedGroup $bareGroup
    if ($operation -eq "UPDATE" -and $table -eq "set") {
        $prefixStart = [Math]::Max(0, $match.Index - 20)
        $prefix = $content.Substring($prefixStart, $match.Index - $prefixStart)
        if ($prefix -match '(?i)DO\s*$') {
            return
        }
    }
    $owner = $statementTableOwner[$table]
    $lineNumber = 1 + ($content.Substring(0, $match.Index).Split("`n").Count - 1)
    if (-not $owner) {
        Write-Host "FAIL $operation targets unknown table '$table' in ${filePath}:$lineNumber"
        exit 1
    }
    if ($owner -ne $serviceKey) {
        Write-Host "FAIL cross-owner $operation in production split SQL: ${filePath}:$lineNumber targets $table owned by $owner"
        exit 1
    }
}

$schemaColumns = @{
    "activity" = New-ColumnSet @("id", "category_id", "artist_id", "per_user_limit", "real_name_required", "ticket_transfer_allowed")
    "activity_marketing_rule" = New-ColumnSet @("id", "activity_id", "enabled", "coupon_name", "discount_type", "threshold_amount", "discount_amount", "max_coupon_count", "per_user_limit", "claimed_count", "used_count", "status", "start_time", "end_time", "create_time", "update_time")
    "activity_question" = New-ColumnSet @("id", "activity_id", "user_id", "content", "answer", "answered_by", "status", "create_time", "answered_at")
    "activity_review" = New-ColumnSet @("id", "activity_id", "user_id", "order_id", "rating", "content", "images", "like_count", "status", "create_time")
    "activity_review_report" = New-ColumnSet @("id", "review_id", "activity_id", "user_id", "reason", "status", "handled_by", "handle_note", "create_time", "handled_at")
    "activity_risk_resolution" = New-ColumnSet @("id", "activity_id", "organizer_id", "risk_artist_id", "status", "resolution_note", "review_note", "submitted_by", "reviewed_by", "reviewed_at", "create_time", "update_time")
    "activity_seat_layout" = New-ColumnSet @("id", "activity_id", "source_venue_layout_id")
    "activity_seat_layout_section" = New-ColumnSet @("id", "activity_layout_id")
    "artist" = New-ColumnSet @("id")
    "activity_artist" = New-ColumnSet @("id", "activity_id", "artist_id", "sort", "is_primary", "role_type", "role_name", "visibility", "status", "create_time", "update_time")
    "category" = New-ColumnSet @("id")
    "grab_request" = New-ColumnSet @("id", "request_id", "idempotency_key", "user_id", "session_id", "ticket_type_id", "quantity", "seat_ids", "attendee_ids", "allocate_random", "status", "order_id", "fail_reason", "expire_time", "created_at", "updated_at")
    "notification" = New-ColumnSet @("id")
    "notification_delivery" = New-ColumnSet @("id", "event_id", "event_type", "user_id", "order_id", "activity_id", "channel", "status", "failure_reason", "retry_count", "provider_message_id", "template_code", "content_snapshot", "payload_json", "created_time", "updated_time", "sent_time")
    "mq_dead_letter" = New-ColumnSet @("id", "source_queue", "dead_letter_queue", "payload", "failure_message", "status", "replay_count", "replayed_at", "created_time", "updated_time")
    "search_index_dead_letter" = New-ColumnSet @("id", "source_queue", "dead_letter_queue", "payload", "failure_message", "status", "replay_count", "replayed_at", "created_time", "updated_time")
    "order" = New-ColumnSet @("id")
    "order_attendee" = New-ColumnSet @("id", "order_id", "order_seat_id", "user_id", "session_id", "ticket_type_id", "attendee_user_profile_id", "real_name", "id_type", "id_no_hash", "id_no_mask", "id_no_encrypted", "phone", "status", "create_time", "update_time")
    "order_seat" = New-ColumnSet @("id", "order_id")
    "order_snapshot" = New-ColumnSet @("id", "order_id", "ticket_transfer_allowed")
    "electronic_ticket" = New-ColumnSet @("id", "ticket_no", "order_id", "order_seat_id", "user_id", "original_user_id", "session_id", "ticket_type_id", "attendee_user_profile_id", "real_name", "id_type", "id_no_mask", "phone", "seat_label", "status", "checked_in_at", "invalid_reason", "create_time", "update_time")
    "check_in_device" = New-ColumnSet @("id", "device_code", "device_name", "organizer_id", "session_id", "status", "secret_hash", "last_seen_at", "create_time", "update_time")
    "ticket_check_in_record" = New-ColumnSet @("id", "request_id", "ticket_id", "ticket_no", "order_id", "user_id", "session_id", "ticket_type_id", "device_code", "operator_user_id", "channel", "result", "failure_reason", "checked_in_at", "create_time")
    "organizer_application" = New-ColumnSet @("id", "user_id", "reviewer_id")
    "organizer_application_material" = New-ColumnSet @("id", "application_id", "asset_id", "material_type", "create_time")
    "payment" = New-ColumnSet @("id")
    "performance_subscription" = New-ColumnSet @("id", "user_id", "target_type", "target_id", "target_value", "target_name", "activity_id", "artist_id", "city", "remind_before_minutes", "status", "create_time", "update_time")
    "privacy_audit_log" = New-ColumnSet @("id", "actor_user_id", "action", "target_type", "target_id", "detail", "create_time")
    "private_asset" = New-ColumnSet @("id", "uploader_id", "biz_id")
    "rbac_role" = New-ColumnSet @("code", "name", "status", "create_time", "update_time")
    "rbac_permission" = New-ColumnSet @("code", "name", "description", "status", "create_time", "update_time")
    "rbac_role_permission" = New-ColumnSet @("role_code", "permission_code", "create_time")
    "user_permission_override" = New-ColumnSet @("id", "user_id", "permission_code", "override_type", "reason", "create_by", "create_time", "update_time")
    "operation_audit_log" = New-ColumnSet @("id", "operator_id", "operator_role", "action", "target_type", "target_id", "target_ref", "reason", "result", "success", "error_message", "trace_id", "create_time")
    "organizer_ops_assignment" = New-ColumnSet @("organizer_user_id", "assigned_operator_id", "risk_level", "status", "next_follow_at", "last_follow_at", "create_time", "update_time")
    "organizer_ops_follow_up" = New-ColumnSet @("id", "organizer_user_id", "operator_id", "follow_type", "content", "next_follow_at", "create_time")
    "exception_task" = New-ColumnSet @("id", "task_type", "business_no", "order_no", "payment_no", "refund_no", "ticket_no", "severity", "status", "reason", "result", "operator_id", "operator_role", "trace_id", "create_time", "update_time")
    "exception_task_evidence" = New-ColumnSet @("id", "exception_id", "url", "create_time")
    "reconciliation_batch" = New-ColumnSet @("id", "batch_no", "biz_date", "source_type", "status", "summary_json", "create_time", "update_time")
    "reconciliation_detail" = New-ColumnSet @("id", "batch_no", "business_no", "business_type", "expected_amount", "actual_amount", "status", "create_time")
    "reconciliation_difference" = New-ColumnSet @("id", "batch_no", "diff_type", "business_no", "expected_amount", "actual_amount", "diff_amount", "reason", "status", "create_time")
    "refund_request" = New-ColumnSet @("id", "payment_id", "quantity", "order_seat_ids", "refund_type")
    "reservation" = New-ColumnSet @("id", "session_id")
    "search_history" = New-ColumnSet @("id", "user_id", "keyword", "search_count", "last_searched_at", "create_time", "update_time")
    "seat" = New-ColumnSet @("id", "session_id", "ticket_type_id")
    "seat_block" = New-ColumnSet @("id")
    "seat_override" = New-ColumnSet @("id", "block_id")
    "session" = New-ColumnSet @("id", "activity_id", "venue_id")
    "session_seat" = New-ColumnSet @("id", "session_id", "venue_id", "area_id", "venue_seat_id", "ticket_type_id", "layout_section_id", "seat_block_id", "status", "order_id", "lock_expire_time", "row_no", "seat_no", "lock_request_id")
    "session_seat_layout" = New-ColumnSet @("id", "session_id", "activity_layout_id")
    "session_seat_layout_section" = New-ColumnSet @("id", "session_layout_id", "activity_layout_section_id", "ticket_type_id")
    "seat_layout_version" = New-ColumnSet @("id", "owner_type", "owner_id", "version_no", "version_status", "name", "template_type", "stage_title", "stage_x", "stage_y", "canvas_width", "canvas_height", "base_version_id", "published_at", "published_by", "create_time", "update_time")
    "seat_layout_version_block" = New-ColumnSet @("id", "version_id", "block_key", "name", "block_type", "x", "y", "rotation", "scale", "rows", "cols", "seats_per_row", "row_spacing", "seat_spacing", "inner_radius", "arc_start_angle", "arc_end_angle", "width", "height", "capacity", "polygon_points", "color", "sort", "status", "create_time", "update_time")
    "seat_layout_version_group_binding" = New-ColumnSet @("id", "version_id", "block_key", "group_key", "binding_role", "sort", "create_time", "update_time")
    "seat_layout_version_override" = New-ColumnSet @("id", "version_block_id", "row_no", "seat_no", "status", "dx", "dy", "custom_label", "create_time", "update_time")
    "seat_layout_version_ticket_group" = New-ColumnSet @("id", "version_id", "group_key", "name", "default_price", "activity_price", "sort", "status", "create_time", "update_time")
    "sms_code" = New-ColumnSet @("id")
    "station" = New-ColumnSet @("id", "tour_id")
    "station_config_version" = New-ColumnSet @("id", "station_id", "activity_id", "tour_id", "venue_id", "venue_application_id", "reviewer_id", "created_by")
    "stock_log" = New-ColumnSet @("id", "session_id", "ticket_type_id")
    "support_account" = New-ColumnSet @("id", "user_id", "phone", "nickname", "status", "create_time", "update_time")
    "support_conversation" = New-ColumnSet @("id", "user_id", "subject", "status", "source_type", "assigned_agent_id", "skill_group_id", "last_message", "create_time", "update_time", "closed_at", "first_response_due_at", "first_agent_replied_at", "last_user_message_at", "last_agent_message_at", "close_request_reason", "close_requested_by", "close_requested_at", "escalated_to_admin", "escalation_reason", "escalated_at", "sla_timeout_flag")
    "support_conversation_note" = New-ColumnSet @("id", "conversation_id", "author_user_id", "content", "create_time")
    "support_conversation_tag" = New-ColumnSet @("conversation_id", "tag_code", "create_by", "create_time")
    "support_conversation_audit" = New-ColumnSet @("id", "conversation_id", "actor_user_id", "action", "from_status", "to_status", "detail", "create_time")
    "support_quick_reply" = New-ColumnSet @("id", "category", "title", "content", "status", "sort_order", "create_time")
    "support_ai_suggestion" = New-ColumnSet @("id", "conversation_id", "agent_id", "message_cutoff", "context_digest", "suggestion_text", "summary", "issue_type", "recommended_action", "missing_information", "source_evidence", "model", "prompt_version", "status", "edited_text", "failure_code", "failure_reason", "reject_reason", "create_time", "update_time", "accepted_at", "edited_at", "rejected_at")
    "support_message" = New-ColumnSet @("id", "conversation_id", "sender_user_id", "sender_type", "content", "create_time")
    "cs_skill_group" = New-ColumnSet @("id", "group_code", "group_name", "leader_user_id", "status", "create_time", "update_time")
    "cs_agent_member" = New-ColumnSet @("id", "group_id", "user_id", "agent_name", "agent_status", "create_time", "update_time")
    "cs_session_audit" = New-ColumnSet @("id", "session_id", "auditor_user_id", "score", "comments", "is_resolved", "create_time")
    "team_grab_request" = New-ColumnSet @("id", "request_id", "grab_request_id", "team_id", "trigger_user_id", "payer_user_id", "session_id", "ticket_type_id", "order_id")
    "team_seat_assignment" = New-ColumnSet @("id", "team_id", "user_id", "order_id", "order_seat_id", "session_seat_id", "seat_label")
    "ticket_team" = New-ColumnSet @("id", "invite_code", "leader_user_id", "activity_id", "session_id", "ticket_type_id")
    "ticket_transfer" = New-ColumnSet @("id", "transfer_code", "ticket_id", "new_ticket_id", "from_user_id", "to_user_id", "status", "expires_at", "claimed_at", "create_time", "update_time")
    "ticket_team_member" = New-ColumnSet @("id", "team_id", "session_id", "user_id", "seat_id", "order_seat_id")
    "ticket_group" = New-ColumnSet @("id")
    "ticket_asset" = New-ColumnSet @("id", "uploader_id")
    "ticket_type" = New-ColumnSet @("id", "session_id")
    "ticket_type_area" = New-ColumnSet @("id", "ticket_type_id", "session_id", "area_id")
    "tour" = New-ColumnSet @("id")
    "user" = New-ColumnSet @("id")
    "user_attendee" = New-ColumnSet @("id", "user_id", "real_name", "id_type", "id_no_hash", "id_no_mask", "id_no_encrypted", "phone", "is_default", "status", "create_time", "update_time")
    "user_asset" = New-ColumnSet @("id", "uploader_id")
    "user_auth" = New-ColumnSet @("id", "user_id")
    "user_browse_history" = New-ColumnSet @("id", "user_id", "activity_id", "activity_name", "poster", "category", "artist", "city", "viewed_at", "create_time", "update_time")
    "venue" = New-ColumnSet @("id", "venue_name_en", "venue_type", "province", "district")
    "venue_application" = New-ColumnSet @("id", "venue_id", "proof_asset_id", "venue_name_en", "venue_type", "province", "district")
    "venue_application_material" = New-ColumnSet @("id", "venue_application_id", "material_type", "asset_id", "note", "valid_from", "valid_to", "create_time", "update_time")
    "venue_area" = New-ColumnSet @("id", "venue_id")
    "venue_default_layout" = New-ColumnSet @("id", "venue_id")
    "venue_default_layout_section" = New-ColumnSet @("id", "layout_id")
    "venue_seat" = New-ColumnSet @("id", "venue_id", "area_id")
    "waitlist_allocation_log" = New-ColumnSet @("id", "event_key", "attempt_no", "session_id", "ticket_type_id", "released_quantity", "allocated_entry_id", "order_id", "source_order_id", "status", "message", "create_time")
    "waitlist_entry" = New-ColumnSet @("id", "user_id", "session_id", "ticket_type_id", "quantity", "attendee_ids", "seat_preference", "status", "priority_no", "offer_order_id", "offer_expire_time", "fail_reason", "create_time", "update_time")
    "waitlist_offer" = New-ColumnSet @("id", "entry_id", "user_id", "session_id", "ticket_type_id", "quantity", "order_id", "status", "expire_time", "create_time", "update_time")
}

foreach ($key in $expectedKeys) {
    $dir = Join-Path -Path $splitRoot -ChildPath $key
    if (-not (Test-Path -LiteralPath $dir)) {
        Write-Host "FAIL missing production split directory: $dir"
        exit 1
    }
}

$tableOwner = @{}
foreach ($service in $manifest.services) {
    foreach ($table in $service.tables) {
        $normalized = $table.ToLower()
        if (-not $schemaColumns.ContainsKey($normalized)) {
            Write-Host "FAIL manifest references unknown production table '$table' for service $($service.key)"
            exit 1
        }
        if ($tableOwner.ContainsKey($normalized)) {
            Write-Host "FAIL table assigned to multiple services: $table"
            exit 1
        }
        $tableOwner[$normalized] = $service.key
    }
}

$sqlFiles = Get-ChildItem -Path $splitRoot -Filter "*.sql" -Recurse | Sort-Object FullName
if ($sqlFiles.Count -eq 0) {
    Write-Host "FAIL no production split SQL files found"
    exit 1
}

$forbiddenText = @(
    "sql/local/",
    "20260520_drop_cross_owner_fks_local_only.sql",
    "20260520_move_tables_to_service_schemas_local_only.sql",
    "DROP DATABASE",
    "DROP SCHEMA"
)

foreach ($file in $sqlFiles) {
    $content = Get-Content -Raw -LiteralPath $file.FullName
    if ($content -notmatch '(?m)^-- owner: (java-(user|ticket|order|payment|notification)|grab-service)') {
        Write-Host "FAIL SQL file missing owner comment: $($file.FullName)"
        exit 1
    }
    foreach ($text in $forbiddenText) {
        if ($content -like "*$text*") {
            Write-Host "FAIL SQL file contains forbidden text '$text': $($file.FullName)"
            exit 1
        }
    }
}

$constraintNames = @{}
$identifierPattern = '(?:(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*)?(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))'
$constraintPattern = 'ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?' + $identifierPattern + '\s+ADD\s+CONSTRAINT\s+(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))'
function Test-HasPrecedingConstraintDrop {
    param(
        [string]$Content,
        [string]$TableName,
        [string]$ConstraintName,
        [int]$BeforeIndex
    )

    $precedingContent = $Content.Substring(0, $BeforeIndex)
    $dropPattern = 'ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?' + $identifierPattern + '\s+DROP\s+CONSTRAINT\s+(?:IF\s+EXISTS\s+)?(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))'
    $normalizedTable = (Normalize-Identifier $TableName)
    $normalizedConstraint = $ConstraintName.ToLower()

    foreach ($dropMatch in [regex]::Matches($precedingContent, $dropPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        $dropTable = Get-IdentifierFromMatch $dropMatch 1 2
        if ($dropTable -ne $normalizedTable) {
            continue
        }

        $dropConstraint = $dropMatch.Groups[3].Value
        if (-not $dropConstraint) {
            $dropConstraint = $dropMatch.Groups[4].Value
        }
        if ($dropConstraint.ToLower() -eq $normalizedConstraint) {
            return $true
        }
    }

    return $false
}

foreach ($file in $sqlFiles) {
    $content = Get-Content -Raw -LiteralPath $file.FullName
    foreach ($match in [regex]::Matches($content, $constraintPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        $tableName = Get-IdentifierFromMatch $match 1 2
        $constraintName = $match.Groups[3].Value
        if (-not $constraintName) {
            $constraintName = $match.Groups[4].Value
        }
        $normalized = $constraintName.ToLower()
        $lineNumber = 1 + ($content.Substring(0, $match.Index).Split("`n").Count - 1)
        if ($constraintNames.ContainsKey($normalized)) {
            $firstSeen = $constraintNames[$normalized]
            if (($tableName -eq $firstSeen.Table) -and (Test-HasPrecedingConstraintDrop $content $tableName $constraintName $match.Index)) {
                continue
            }
            Write-Host "FAIL duplicate constraint name '$constraintName' on table '$tableName' in $($file.FullName):$lineNumber; first seen on table '$($firstSeen.Table)' at $($firstSeen.Location)"
            exit 1
        }
        $constraintNames[$normalized] = @{
            Location = "$($file.FullName):$lineNumber"
            Table = $tableName
        }
    }
}

$createTablePattern = 'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?' + $identifierPattern
$alterTablePattern = 'ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?' + $identifierPattern
$updatePattern = 'UPDATE\s+(?:ONLY\s+)?' + $identifierPattern
$createIndexPattern = 'CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)\s+ON\s+' + $identifierPattern
$fkPattern = 'ALTER\s+TABLE\s+' + $identifierPattern + '\s+ADD\s+CONSTRAINT\s+(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)\s+FOREIGN\s+KEY\s*\(([^\)]+)\)\s+REFERENCES\s+' + $identifierPattern + '\s*\(([^\)]+)\)'
foreach ($file in $sqlFiles) {
    $serviceKey = Split-Path -Leaf (Split-Path -Parent $file.FullName)
    $content = Get-Content -Raw -LiteralPath $file.FullName
    $statementTableOwner = $tableOwner.Clone()
    foreach ($match in [regex]::Matches($content, $createTablePattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        $table = Get-IdentifierFromMatch $match 1 2
        $owner = $statementTableOwner[$table]
        $lineNumber = 1 + ($content.Substring(0, $match.Index).Split("`n").Count - 1)
        if ($owner -and $owner -ne $serviceKey) {
            Write-Host "FAIL cross-owner CREATE TABLE in production split SQL: $($file.FullName):$lineNumber targets $table owned by $owner"
            exit 1
        }
        $statementTableOwner[$table] = $serviceKey
    }
    foreach ($match in [regex]::Matches($content, $alterTablePattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        Test-SqlStatementTargetOwner $match $file.FullName $serviceKey 1 2 "ALTER TABLE"
    }
    foreach ($match in [regex]::Matches($content, $updatePattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        Test-SqlStatementTargetOwner $match $file.FullName $serviceKey 1 2 "UPDATE"
    }
    foreach ($match in [regex]::Matches($content, $createIndexPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        Test-SqlStatementTargetOwner $match $file.FullName $serviceKey 1 2 "CREATE INDEX"
    }
    foreach ($match in [regex]::Matches($content, $fkPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        $child = Get-IdentifierFromMatch $match 1 2
        $childColumns = Get-ColumnList $match.Groups[3].Value
        $referenced = Get-IdentifierFromMatch $match 4 5
        $referencedColumns = Get-ColumnList $match.Groups[6].Value
        $childOwner = $tableOwner[$child]
        $owner = $tableOwner[$referenced]
        $lineNumber = 1 + ($content.Substring(0, $match.Index).Split("`n").Count - 1)
        if (-not $childOwner) {
            Write-Host "FAIL FK alters unowned table '$child' in $($file.FullName):$lineNumber"
            exit 1
        }
        if ($childOwner -ne $serviceKey) {
            Write-Host "FAIL cross-owner ALTER TABLE in production split SQL: $($file.FullName):$lineNumber alters $child owned by $childOwner"
            exit 1
        }
        if (-not $owner) {
            Write-Host "FAIL FK references unowned table '$referenced' in $($file.FullName):$lineNumber"
            exit 1
        }
        if ($owner -ne $serviceKey) {
            Write-Host "FAIL cross-owner FK in production split SQL: $($file.FullName):$lineNumber references $referenced owned by $owner"
            exit 1
        }
        foreach ($column in $childColumns) {
            if (-not $schemaColumns[$child].ContainsKey($column)) {
                Write-Host "FAIL FK uses unknown child column '$child.$column' in $($file.FullName):$lineNumber"
                exit 1
            }
        }
        foreach ($column in $referencedColumns) {
            if (-not $schemaColumns[$referenced].ContainsKey($column)) {
                Write-Host "FAIL FK references unknown column '$referenced.$column' in $($file.FullName):$lineNumber"
                exit 1
            }
        }
    }
}

$teamSeatLockSql = Join-Path -Path $splitRoot -ChildPath "ticket/20260530_team_seat_lock.sql"
if (-not (Test-Path -LiteralPath $teamSeatLockSql)) {
    Write-Host "FAIL missing team seat lock production SQL: $teamSeatLockSql"
    exit 1
}
$teamSeatLockContent = (Get-Content -Raw -LiteralPath $teamSeatLockSql).ToLower()
if ($teamSeatLockContent -notmatch 'alter\s+table\s+session_seat\s+add\s+column\s+if\s+not\s+exists\s+lock_request_id\s+varchar\s*\(\s*64\s*\)') {
    Write-Host "FAIL team seat lock SQL must add session_seat.lock_request_id: $teamSeatLockSql"
    exit 1
}
$teamSeatLockIndexPattern = 'create\s+index\s+if\s+not\s+exists\s+idx_session_seat_team_lock_lookup\s+on\s+session_seat\s*\(\s*session_id\s*,\s*ticket_type_id\s*,\s*status\s*,\s*seat_block_id\s*,\s*\(case\s+when\s+seat_block_id\s+is\s+null\s+then\s+layout_section_id\s+end\)\s*,\s*row_no\s*,\s*seat_no\s*,\s*id\s*\)\s*where\s+order_id\s+is\s+null\s+and\s+lock_expire_time\s+is\s+null'
if ($teamSeatLockContent -notmatch $teamSeatLockIndexPattern) {
    Write-Host "FAIL team seat lock index must match candidate query ordering and partial predicate: $teamSeatLockSql"
    exit 1
}
if ($teamSeatLockContent -notmatch 'create\s+index\s+if\s+not\s+exists\s+idx_session_seat_lock_request\s+on\s+session_seat\s*\(\s*lock_request_id\s*\)\s*where\s+lock_request_id\s+is\s+not\s+null') {
    Write-Host "FAIL team seat lock SQL must index session_seat.lock_request_id: $teamSeatLockSql"
    exit 1
}

$seatSelectionModeSql = Join-Path -Path $splitRoot -ChildPath "order/20260530_order_seat_selection_mode.sql"
if (-not (Test-Path -LiteralPath $seatSelectionModeSql)) {
    Write-Host "FAIL missing order seat selection mode production SQL: $seatSelectionModeSql"
    exit 1
}
$seatSelectionModeContent = (Get-Content -Raw -LiteralPath $seatSelectionModeSql).ToLower()
$hasSeatSelectionUpdate = $seatSelectionModeContent -match '(?is)update\s+order_snapshot\s+\w+\s+set\s+seat_selection_mode\s*=\s*case'
$hasTeamOrderInference = $seatSelectionModeContent -match "team_order"
$hasOrderSeatInference = $seatSelectionModeContent -match "(?is)exists\s*\(\s*select\s+1\s+from\s+order_seat"
if (-not $hasSeatSelectionUpdate -or -not $hasTeamOrderInference -or -not $hasOrderSeatInference) {
    Write-Host "FAIL order seat selection mode SQL must backfill from team_order and order_seat: $seatSelectionModeSql"
    exit 1
}

$teamGrabSql = Join-Path -Path $splitRoot -ChildPath "grab/20260530_team_grab.sql"
if (-not (Test-Path -LiteralPath $teamGrabSql)) {
    Write-Host "FAIL missing team grab production SQL: $teamGrabSql"
    exit 1
}
$teamGrabContent = (Get-Content -Raw -LiteralPath $teamGrabSql).ToLower()
foreach ($tableName in @("ticket_team", "ticket_team_member", "team_grab_request", "team_seat_assignment")) {
    if ($teamGrabContent -notmatch ("create\s+table\s+if\s+not\s+exists\s+" + $tableName + "\s*\(")) {
        Write-Host "FAIL team grab SQL must create ${tableName}: $teamGrabSql"
        exit 1
    }
}
if ($teamGrabContent -notmatch 'seat_label\s+varchar\s*\(\s*128\s*\)') {
    Write-Host "FAIL team grab SQL must store order-owned seat labels on team assignments: $teamGrabSql"
    exit 1
}
if ($teamGrabContent -notmatch 'alter\s+table\s+team_grab_request\s+add\s+column\s+if\s+not\s+exists\s+grab_request_id\s+varchar\s*\(\s*64\s*\)') {
    Write-Host "FAIL team grab SQL must add grab_request_id for existing tables: $teamGrabSql"
    exit 1
}
if ($teamGrabContent -notmatch "team-grab-legacy-") {
    Write-Host "FAIL team grab SQL must backfill legacy grab_request_id values with a distinct prefix: $teamGrabSql"
    exit 1
}
$hasGrabRequestIdNullPreflight = $teamGrabContent -match '(?is)if\s+exists\s*\(\s*select\s+1\s+from\s+team_grab_request\s+where\s+grab_request_id\s+is\s+null\s*\)'
$hasGrabRequestIdDuplicatePreflight = $teamGrabContent -match '(?is)if\s+exists\s*\(\s*select\s+1\s+from\s+team_grab_request\s+group\s+by\s+grab_request_id\s+having\s+count\s*\(\s*\*\s*\)\s*>\s*1\s*\)'
if (-not $hasGrabRequestIdNullPreflight -or -not $hasGrabRequestIdDuplicatePreflight) {
    Write-Host "FAIL team grab SQL must preflight null and duplicate grab_request_id values before enforcing not null/unique index: $teamGrabSql"
    exit 1
}
if ($teamGrabContent -notmatch 'alter\s+table\s+team_grab_request\s+alter\s+column\s+grab_request_id\s+set\s+not\s+null') {
    Write-Host "FAIL team grab SQL must make grab_request_id not null after backfill: $teamGrabSql"
    exit 1
}
if ($teamGrabContent -notmatch 'create\s+unique\s+index\s+if\s+not\s+exists\s+uk_team_grab_request_grab_request_id\s+on\s+team_grab_request\s*\(\s*grab_request_id\s*\)') {
    Write-Host "FAIL team grab SQL must create an idempotent unique index for grab_request_id: $teamGrabSql"
    exit 1
}

Write-Host "PASS production split SQL safety check"
