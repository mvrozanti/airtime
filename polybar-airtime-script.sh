#!/usr/bin/env bash

# Polybar script for Airtime/Smoke Timer
# Syncs with Android app via Abacus API
#
# Usage:
#   SMOKE_PLACE_NAME="Home" ./polybar-airtime-script.sh           # Display emoji
#   SMOKE_PLACE_NAME="Home" ./polybar-airtime-script.sh click-left   # Lock timer
#   SMOKE_PLACE_NAME="Home" ./polybar-airtime-script.sh click-middle # Show remaining time

# Configuration
ABACUS_BASE_URL="https://abacus.jasoncameron.dev"
ABACUS_NAMESPACE="airtime"
# IMPORTANT: Place name must match EXACTLY (case-sensitive) with the place name in the Android app
# Example: If app has "Home", use SMOKE_PLACE_NAME="Home" (not "home")
PLACE_NAME="${SMOKE_PLACE_NAME:-default}"  # Use "default" or set SMOKE_PLACE_NAME env var

# Local state files (used as cache/fallback)
STATE_FILE="/tmp/smoke_timer.state"
END_FILE="/tmp/smoke_timer.end"
INCREMENT_FILE="/tmp/smoke_timer.increment"
ADMIN_KEYS_FILE="/tmp/smoke_timer_admin_keys"  # Store admin keys for writing to Abacus

# Base lock duration (45 minutes in seconds)
BASE_LOCK=$((45*60))

# Abacus API helper functions
abacus_get() {
    local key="$1"
    local url="${ABACUS_BASE_URL}/get/${ABACUS_NAMESPACE}/${key}"
    local response=$(curl -s --connect-timeout 5 --max-time 5 "$url" 2>/dev/null)
    
    # Check if response is empty
    if [ -z "$response" ]; then
        echo ""
        return
    fi
    
    # Check if response is a JSON error (but not "Key not found" which is expected for new keys)
    if echo "$response" | grep -q '"error"'; then
        # Check if it's a rate limit error - in that case, return empty but don't fail
        if echo "$response" | grep -q "Too many requests"; then
            echo ""
            return
        fi
        # For other errors like "Key not found", return empty
        echo ""
        return
    fi
    
    # Check if response is JSON with "value" field (e.g., {"value": 123})
    # Match JSON with "value" field - escape the brace properly
    if echo "$response" | grep -q '"value"'; then
        # Extract value from JSON using grep - try multiple patterns
        local value=$(echo "$response" | grep -oE '"value"\s*:\s*[0-9]+' | grep -oE '[0-9]+')
        if [ -z "$value" ]; then
            # Try without spaces
            value=$(echo "$response" | grep -oE '"value":[0-9]+' | grep -oE '[0-9]+')
        fi
        if [ -n "$value" ]; then
            echo "$value"
            return
        fi
    fi
    
    # Check if response is plain numeric (backward compatibility)
    if echo "$response" | grep -qE '^[0-9]+$'; then
        echo "$response"
        return
    fi
    
    # If response is "null" or other non-numeric, return empty
    if [ "$response" = "null" ] || [ "$response" = "{}" ]; then
        echo ""
        return
    fi
    
    # For other cases, return as-is
    echo "$response"
}

abacus_create() {
    local key="$1"
    local url="${ABACUS_BASE_URL}/create/${ABACUS_NAMESPACE}/${key}"
    local response=$(curl -s --connect-timeout 5 --max-time 5 -X POST "$url" 2>/dev/null)
    
    # Check if response contains an admin key
    if [ -n "$response" ]; then
        # Try to extract admin key from JSON or use as-is
        local admin_key=$(echo "$response" | grep -oE '"admin_key"\s*:\s*"[^"]+"' | grep -oE '"[^"]+"' | tr -d '"')
        if [ -z "$admin_key" ]; then
            # Not JSON, might be plain text admin key
            admin_key=$(echo "$response" | grep -v "error" | head -1)
        fi
        if [ -n "$admin_key" ] && [ "$admin_key" != "null" ]; then
            echo "$admin_key"
            return 0
        fi
    fi
    return 1
}

abacus_get_admin_key() {
    local key="$1"
    # Check if we have a stored admin key
    if [ -f "$ADMIN_KEYS_FILE" ]; then
        grep "^${key}=" "$ADMIN_KEYS_FILE" | cut -d'=' -f2-
    fi
}

abacus_store_admin_key() {
    local key="$1"
    local admin_key="$2"
    # Store admin key in file
    if [ ! -f "$ADMIN_KEYS_FILE" ]; then
        touch "$ADMIN_KEYS_FILE"
        chmod 600 "$ADMIN_KEYS_FILE"
    fi
    # Remove old entry if exists
    grep -v "^${key}=" "$ADMIN_KEYS_FILE" > "${ADMIN_KEYS_FILE}.tmp" 2>/dev/null || true
    echo "${key}=${admin_key}" >> "${ADMIN_KEYS_FILE}.tmp"
    mv "${ADMIN_KEYS_FILE}.tmp" "$ADMIN_KEYS_FILE"
}

abacus_set() {
    local key="$1"
    local value="$2"
    # Use query parameter format (matches Android app)
    local url="${ABACUS_BASE_URL}/set/${ABACUS_NAMESPACE}/${key}?value=${value}"
    
    # Get admin key (try stored first, then create if needed)
    local admin_key=$(abacus_get_admin_key "$key")
    if [ -z "$admin_key" ]; then
        # Try to create counter to get admin key
        admin_key=$(abacus_create "$key")
        if [ -n "$admin_key" ]; then
            abacus_store_admin_key "$key" "$admin_key"
        else
            # Can't write without admin key, fail silently
            return 1
        fi
    fi
    
    # Use admin key to write
    local response=$(curl -s --connect-timeout 5 --max-time 5 -X POST \
        -H "Authorization: Bearer ${admin_key}" \
        "$url" 2>/dev/null)
    
    # Check if we got a 401 (invalid token) - clear admin key and retry
    if echo "$response" | grep -q "401\|invalid\|token"; then
        # Clear stored admin key
        grep -v "^${key}=" "$ADMIN_KEYS_FILE" > "${ADMIN_KEYS_FILE}.tmp" 2>/dev/null || true
        mv "${ADMIN_KEYS_FILE}.tmp" "$ADMIN_KEYS_FILE" 2>/dev/null || true
        # Try to recreate
        admin_key=$(abacus_create "$key")
        if [ -n "$admin_key" ]; then
            abacus_store_admin_key "$key" "$admin_key"
            # Retry with new key
            curl -s --connect-timeout 5 --max-time 5 -X POST \
                -H "Authorization: Bearer ${admin_key}" \
                "$url" >/dev/null 2>&1
        fi
    fi
}

abacus_track() {
    local key="$1"
    local url="${ABACUS_BASE_URL}/hit/${ABACUS_NAMESPACE}/${key}"
    curl -s --connect-timeout 5 --max-time 5 "$url" >/dev/null 2>&1
}

# Sync state from Abacus to local files
sync_from_abacus() {
    # Get lock state (stored as is_locked in Android) - this is the source of truth
    local locked_value=$(abacus_get "${PLACE_NAME}_is_locked")
    if [ -n "$locked_value" ]; then
        if [ "$locked_value" = "1" ] || [ "$locked_value" = "true" ]; then
            echo "locked" > "$STATE_FILE"
        else
            echo "unlocked" > "$STATE_FILE"
        fi
    fi
    
    # Get lock end timestamp
    local end_time=$(abacus_get "${PLACE_NAME}_lock_end_timestamp")
    if [ -n "$end_time" ] && [ "$end_time" != "0" ] && echo "$end_time" | grep -qE '^[0-9]+$'; then
        # Convert from milliseconds to seconds if needed
        if [ "$end_time" -gt 10000000000 ]; then
            # Timestamp is in milliseconds, convert to seconds
            end_time=$((end_time / 1000))
        fi
        echo "$end_time" > "$END_FILE"
        
        # If we couldn't get is_locked but we have a valid future timestamp, assume locked
        if [ -z "$locked_value" ]; then
            NOW=$(date +%s)
            REM=$((end_time - NOW))
            if [ "$REM" -gt 0 ]; then
                # Timestamp is in the future, assume locked
                echo "locked" > "$STATE_FILE"
            else
                # Timestamp expired, assume unlocked
                echo "unlocked" > "$STATE_FILE"
            fi
        fi
    fi
    
    # Timestamp handling moved up to work with is_locked check
    
    # Get increment
    local increment=$(abacus_get "${PLACE_NAME}_increment")
    if [ -n "$increment" ] && echo "$increment" | grep -qE '^[0-9]+$'; then
        echo "$increment" > "$INCREMENT_FILE"
    fi
}

# Sync state from local files to Abacus
sync_to_abacus() {
    # Note: This won't work without admin keys, but kept for backward compatibility
    # The Android app handles all writes to Abacus
    if [ -f "$STATE_FILE" ]; then
        local state=$(cat "$STATE_FILE")
        if [ "$state" = "locked" ]; then
            abacus_set "${PLACE_NAME}_is_locked" "1"
        else
            abacus_set "${PLACE_NAME}_is_locked" "0"
        fi
    fi
    
    if [ -f "$END_FILE" ]; then
        local end_time=$(cat "$END_FILE")
        # Convert to milliseconds if needed
        if [ "$end_time" -lt 10000000000 ]; then
            end_time=$((end_time * 1000))
        fi
        abacus_set "${PLACE_NAME}_lock_end_timestamp" "$end_time"
    fi
    
    if [ -f "$INCREMENT_FILE" ]; then
        local increment=$(cat "$INCREMENT_FILE")
        abacus_set "${PLACE_NAME}_increment" "$increment"
    fi
}

# Initialize local files if they don't exist
if [ ! -f "$STATE_FILE" ]; then
    echo "unlocked" > "$STATE_FILE"
fi
if [ ! -f "$INCREMENT_FILE" ]; then
    echo 0 > "$INCREMENT_FILE"
fi

# Try to sync from Abacus on startup (but don't fail if API is down)
sync_from_abacus 2>/dev/null || true

# Read current state (from local cache)
STATE=$(cat "$STATE_FILE")
INCREMENT=$(cat "$INCREMENT_FILE" 2>/dev/null || echo "0")

# Try to get base duration and increment step from Abacus (with fallback)
# Check v2 keys first, then v1, then old format for backward compatibility
BASE_DURATION_MINUTES=$(abacus_get "${PLACE_NAME}_base_duration_minutes_config_v2" 2>/dev/null)
if [ -z "$BASE_DURATION_MINUTES" ] || ! echo "$BASE_DURATION_MINUTES" | grep -qE '^[0-9]+$'; then
    BASE_DURATION_MINUTES=$(abacus_get "${PLACE_NAME}_base_duration_minutes_config" 2>/dev/null)
fi
if [ -z "$BASE_DURATION_MINUTES" ] || ! echo "$BASE_DURATION_MINUTES" | grep -qE '^[0-9]+$'; then
    BASE_DURATION_MINUTES=$(abacus_get "${PLACE_NAME}_base_duration_minutes" 2>/dev/null)
fi
# Validate it's a number, default to 45 if not
if [ -z "$BASE_DURATION_MINUTES" ] || ! echo "$BASE_DURATION_MINUTES" | grep -qE '^[0-9]+$'; then
    BASE_DURATION_MINUTES=45
fi
BASE_LOCK=$((BASE_DURATION_MINUTES * 60))

INCREMENT_STEP_SECONDS=$(abacus_get "${PLACE_NAME}_increment_step_seconds_config_v2" 2>/dev/null)
if [ -z "$INCREMENT_STEP_SECONDS" ] || ! echo "$INCREMENT_STEP_SECONDS" | grep -qE '^[0-9]+$'; then
    INCREMENT_STEP_SECONDS=$(abacus_get "${PLACE_NAME}_increment_step_seconds_config" 2>/dev/null)
fi
if [ -z "$INCREMENT_STEP_SECONDS" ] || ! echo "$INCREMENT_STEP_SECONDS" | grep -qE '^[0-9]+$'; then
    INCREMENT_STEP_SECONDS=$(abacus_get "${PLACE_NAME}_increment_step_seconds" 2>/dev/null)
fi
# Validate it's a number, default to 1 if not
if [ -z "$INCREMENT_STEP_SECONDS" ] || ! echo "$INCREMENT_STEP_SECONDS" | grep -qE '^[0-9]+$'; then
    INCREMENT_STEP_SECONDS=1
fi

if [ "$1" == "click-left" ] && [ "$STATE" == "unlocked" ]; then
    # Calculate lock duration: base + (increment * increment_step)
    LOCK_DURATION=$((BASE_LOCK + (INCREMENT * INCREMENT_STEP_SECONDS)))
    echo "locked" > "$STATE_FILE"
    END_TIME=$(( $(date +%s) + LOCK_DURATION ))
    echo "$END_TIME" > "$END_FILE"
    INCREMENT=$((INCREMENT + 1))
    echo "$INCREMENT" > "$INCREMENT_FILE"
    
    # Sync to Abacus (note: won't work without admin keys, but Android app will sync)
    sync_to_abacus
    abacus_track "${PLACE_NAME}_locks"
    
    # Start background unlock process
    (sleep $LOCK_DURATION && echo "unlocked" > "$STATE_FILE" && rm -f "$END_FILE" && sync_to_abacus) &
fi

# Re-read state after potential lock
STATE=$(cat "$STATE_FILE")

if [ "$1" == "click-middle" ]; then
    # Sync from Abacus first to get latest state
    sync_from_abacus 2>/dev/null || true
    STATE=$(cat "$STATE_FILE")
    
    if [ -f "$END_FILE" ]; then
        END_TIME=$(cat "$END_FILE")
        NOW=$(date +%s)
        REM=$((END_TIME - NOW))
        if [ "$REM" -le 0 ]; then
            echo "unlocked" > "$STATE_FILE"
            rm -f "$END_FILE"
            sync_to_abacus
            notify-send "Smoke Timer" "Unlocked! You can smoke now."
        else
            HOURS=$((REM/3600))
            MIN=$(( (REM%3600)/60 ))
            SEC=$((REM%60))
            notify-send "Smoke Timer" "$HOURS h $MIN m $SEC s left"
        fi
    else
        # Check Abacus for end time
        END_TIME=$(abacus_get "${PLACE_NAME}_lock_end_timestamp" 2>/dev/null || echo "0")
        if [ -n "$END_TIME" ] && [ "$END_TIME" != "0" ] && echo "$END_TIME" | grep -qE '^[0-9]+$'; then
            # Convert from milliseconds to seconds if needed
            if [ "$END_TIME" -gt 10000000000 ]; then
                END_TIME=$((END_TIME / 1000))
            fi
            NOW=$(date +%s)
            REM=$((END_TIME - NOW))
            if [ "$REM" -le 0 ]; then
                echo "unlocked" > "$STATE_FILE"
                sync_to_abacus
                notify-send "Smoke Timer" "Unlocked! You can smoke now."
            else
                HOURS=$((REM/3600))
                MIN=$(( (REM%3600)/60 ))
                SEC=$((REM%60))
                notify-send "Smoke Timer" "$HOURS h $MIN m $SEC s left"
            fi
        else
            notify-send "Smoke Timer" "Unlocked! You can smoke now."
        fi
    fi
fi

# Sync state to Abacus periodically (every time script runs)
# Note: This won't work without admin keys, but Android app handles writes
sync_to_abacus 2>/dev/null || true

# Check if actually locked by verifying timestamp hasn't expired
# If is_locked is 1, we should be locked regardless of timestamp check
# But we still check timestamp to handle expired locks
if [ "$STATE" == "locked" ]; then
    # Check if we have an end time
    if [ -f "$END_FILE" ]; then
        END_TIME=$(cat "$END_FILE")
        NOW=$(date +%s)
        # If timestamp is in milliseconds, convert to seconds
        if [ "$END_TIME" -gt 10000000000 ]; then
            END_TIME=$((END_TIME / 1000))
        fi
        REM=$((END_TIME - NOW))
        if [ "$REM" -le 0 ]; then
            # Lock has expired, update state
            echo "unlocked" > "$STATE_FILE"
            rm -f "$END_FILE"
            STATE="unlocked"
        fi
    else
        # No end file, check Abacus for timestamp
        END_TIME=$(abacus_get "${PLACE_NAME}_lock_end_timestamp" 2>/dev/null || echo "0")
        if [ -n "$END_TIME" ] && [ "$END_TIME" != "0" ] && echo "$END_TIME" | grep -qE '^[0-9]+$'; then
            # Convert from milliseconds to seconds if needed
            if [ "$END_TIME" -gt 10000000000 ]; then
                END_TIME=$((END_TIME / 1000))
            fi
            NOW=$(date +%s)
            REM=$((END_TIME - NOW))
            if [ "$REM" -le 0 ]; then
                # Timestamp expired, but check is_locked to be sure (phone might have extended it)
                LOCKED_CHECK=$(abacus_get "${PLACE_NAME}_is_locked" 2>/dev/null || echo "0")
                if [ "$LOCKED_CHECK" = "1" ]; then
                    # Still locked according to is_locked, update timestamp
                    echo "$END_TIME" > "$END_FILE"
                else
                    # Not locked according to is_locked, unlock
                    echo "unlocked" > "$STATE_FILE"
                    rm -f "$END_FILE"
                    STATE="unlocked"
                fi
            else
                # Still locked, save timestamp
                echo "$END_TIME" > "$END_FILE"
            fi
        else
            # No valid timestamp, but if is_locked says we're locked, trust it
            # Don't change state - is_locked is the source of truth
            # Just try to get timestamp for next time
            END_TIME=$(abacus_get "${PLACE_NAME}_lock_end_timestamp" 2>/dev/null || echo "0")
            if [ -n "$END_TIME" ] && [ "$END_TIME" != "0" ] && echo "$END_TIME" | grep -qE '^[0-9]+$'; then
                if [ "$END_TIME" -gt 10000000000 ]; then
                    END_TIME=$((END_TIME / 1000))
                fi
                echo "$END_TIME" > "$END_FILE"
            fi
        fi
    fi
elif [ "$STATE" != "locked" ]; then
    # If state is not locked, double-check with Abacus
    LOCKED_CHECK=$(abacus_get "${PLACE_NAME}_is_locked" 2>/dev/null || echo "0")
    if [ "$LOCKED_CHECK" = "1" ]; then
        # Actually locked according to Abacus, update state
        echo "locked" > "$STATE_FILE"
        STATE="locked"
        # Try to get timestamp
        END_TIME=$(abacus_get "${PLACE_NAME}_lock_end_timestamp" 2>/dev/null || echo "0")
        if [ -n "$END_TIME" ] && [ "$END_TIME" != "0" ] && echo "$END_TIME" | grep -qE '^[0-9]+$'; then
            if [ "$END_TIME" -gt 10000000000 ]; then
                END_TIME=$((END_TIME / 1000))
            fi
            echo "$END_TIME" > "$END_FILE"
        fi
    fi
fi

# Output emoji based on state
if [ "$STATE" == "locked" ]; then
    echo 🌿
else
    echo 🚬
fi

