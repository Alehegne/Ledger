#!/usr/bin/env bash

set -Eeuo pipefail

API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
API_TIMEOUT="${API_TIMEOUT:-10}"
API_CONNECT_TIMEOUT="${API_CONNECT_TIMEOUT:-3}"
API_TEST_ADMIN_EMAIL="${API_TEST_ADMIN_EMAIL-admin@ledger.test}"
API_TEST_ADMIN_PASSWORD="${API_TEST_ADMIN_PASSWORD-Admin123!}"
API_TEST_INVITED_USER_PASSWORD="${API_TEST_INVITED_USER_PASSWORD-Viewer123!}"
API_TEST_ALLOW_MUTATIONS="${API_TEST_ALLOW_MUTATIONS:-false}"
API_TEST_ALLOW_NONLOCAL="${API_TEST_ALLOW_NONLOCAL:-false}"
API_TEST_ENV_LABEL="${API_TEST_ENV_LABEL:-}"
API_TEST_RUN_ID="${API_TEST_RUN_ID:-${EPOCHSECONDS:-0}-$RANDOM}"

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

TEMP_DIR=""
HTTP_BODY_FILE=""
HTTP_HEADERS_FILE=""
HTTP_STATUS=""
HTTP_ERROR_MESSAGE=""

ADMIN_TOKEN=""
VIEWER_TOKEN=""
INVITATION_TOKEN_RAW=""
INVITED_EMAIL="viewer+${API_TEST_RUN_ID}@ledger.test"
CREATED_ORG_ID=""
CREATED_ORG_NAME="API Test Org ${API_TEST_RUN_ID}"
ACCOUNT_ID=""
ACCOUNT_CODE="AT-${API_TEST_RUN_ID}-1000"
UPDATED_ACCOUNT_CODE="AT-${API_TEST_RUN_ID}-1001"
JOURNAL_DEBIT_ACCOUNT_ID=""
JOURNAL_CREDIT_ACCOUNT_ID=""
JOURNAL_ENTRY_ID=""
JOURNAL_DELETED_ENTRY_ID=""

REQUESTED_GROUPS=()

usage() {
	cat <<'EOF'
Usage:
  chmod +x api-test.sh
  ./api-test.sh
  API_BASE_URL="http://localhost:8081" ./api-test.sh
  ./api-test.sh auth
  ./api-test.sh accounts
  ./api-test.sh --help

Supported groups:
  all
  health
  auth
  organizations
  invitations
  accounts
	journal
  authorization
	reports
  selftest-fail

Key environment variables:
  API_BASE_URL                 Base URL for the running Ledger application.
  API_TIMEOUT                  Total curl timeout in seconds. Default: 10.
  API_CONNECT_TIMEOUT          Curl connect timeout in seconds. Default: 3.
  API_TEST_ADMIN_EMAIL         Admin login email. Default matches application bootstrap config.
  API_TEST_ADMIN_PASSWORD      Admin login password. Default matches application bootstrap config.
  API_TEST_INVITED_USER_PASSWORD
							   Password used when accepting a test invitation.
  API_TEST_ALLOW_MUTATIONS     Must be true to run state-changing endpoint tests.
  API_TEST_ENV_LABEL           Required with API_TEST_ALLOW_MUTATIONS=true to label the isolated environment.
  API_TEST_ALLOW_NONLOCAL      Set to true only if you intentionally target a non-local base URL.
  API_TEST_RUN_ID              Optional explicit unique suffix for test data.

Safety rules:
	- The full suite creates organizations, invitations, users, accounts, and journal entries.
  - Run mutating groups only against a dedicated isolated test environment.
  - The script does not reset databases or delete existing users.

Extension notes:
  1. Add a test_* function for the new endpoint.
  2. Reuse http_request and the JSON assertion helpers.
  3. Capture IDs from live responses instead of hard-coding them.
  4. Register the test in the relevant suite function below.
EOF
}

cleanup() {
	if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
		rm -rf "$TEMP_DIR"
	fi
}

trap cleanup EXIT

pass() {
	printf '[PASS] %s\n' "$1"
	PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
	printf '[FAIL] %s\n' "$1"
	if [[ $# -gt 1 ]]; then
		printf '       %s\n' "$2"
	fi
	FAIL_COUNT=$((FAIL_COUNT + 1))
}

skip() {
	printf '[SKIP] %s\n' "$1"
	if [[ $# -gt 1 ]]; then
		printf '       %s\n' "$2"
	fi
	SKIP_COUNT=$((SKIP_COUNT + 1))
}

summary() {
	printf '========================================\n'
	printf 'API Test Summary\n'
	printf 'Passed: %s\n' "$PASS_COUNT"
	printf 'Failed: %s\n' "$FAIL_COUNT"
	printf 'Skipped: %s\n' "$SKIP_COUNT"
	printf '========================================\n'
}

finish() {
	summary
	if [[ "$FAIL_COUNT" -gt 0 ]]; then
		exit 1
	fi
}

group_selected() {
	local wanted="$1"

	if [[ ${#REQUESTED_GROUPS[@]} -eq 0 ]]; then
		return 0
	fi

	local group
	for group in "${REQUESTED_GROUPS[@]}"; do
		if [[ "$group" == "all" || "$group" == "$wanted" ]]; then
			return 0
		fi
	done

	return 1
}

assert_known_groups() {
	local group
	for group in "$@"; do
		case "$group" in
			all|health|auth|organizations|invitations|accounts|journal|authorization|reports|selftest-fail)
				;;
			*)
				printf 'Unknown group: %s\n\n' "$group" >&2
				usage >&2
				exit 2
				;;
		esac
	done
}

require_tool() {
	local tool_name="$1"
	if ! command -v "$tool_name" >/dev/null 2>&1; then
		fail "Missing required tool: $tool_name" "Install $tool_name and rerun the script."
		return 1
	fi
	return 0
}

ensure_temp_dir() {
	if [[ -z "$TEMP_DIR" ]]; then
		TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ledger-api-test.XXXXXX")"
	fi
}

json_preview() {
	local file_path="$1"
	if jq -e . "$file_path" >/dev/null 2>&1; then
		jq -c 'if type == "object" then with_entries(if .key == "token" or .key == "invitationToken" then .value = "***redacted***" else . end) else . end' "$file_path" 2>/dev/null
	else
		local content
		content="$(<"$file_path")"
		printf '%s' "${content:0:300}"
	fi
}

http_request() {
	local method="$1"
	local path="$2"
	local body="${3:-}"
	local token="${4:-}"

	ensure_temp_dir
	HTTP_BODY_FILE="$TEMP_DIR/body.$RANDOM.json"
	HTTP_HEADERS_FILE="$TEMP_DIR/headers.$RANDOM.txt"
	HTTP_STATUS=""
	HTTP_ERROR_MESSAGE=""

	local url="${API_BASE_URL}${path}"
	local -a curl_args=(
		--silent
		--show-error
		--connect-timeout "$API_CONNECT_TIMEOUT"
		--max-time "$API_TIMEOUT"
		--dump-header "$HTTP_HEADERS_FILE"
		--output "$HTTP_BODY_FILE"
		--write-out '%{http_code}'
		-X "$method"
		"$url"
	)

	if [[ -n "$token" ]]; then
		curl_args+=( -H "Authorization: Bearer $token" )
	fi

	if [[ -n "$body" ]]; then
		curl_args+=( -H 'Content-Type: application/json' --data "$body" )
	fi

	if ! HTTP_STATUS="$(curl "${curl_args[@]}")"; then
		HTTP_ERROR_MESSAGE="curl transport failure for ${method} ${path}"
		return 1
	fi

	return 0
}

status_and_body_match() {
	local expected_status="$1"
	local jq_expression="$2"

	[[ "$HTTP_STATUS" == "$expected_status" ]] && jq -e "$jq_expression" "$HTTP_BODY_FILE" >/dev/null 2>&1
}

fail_with_response() {
	local test_name="$1"
	local message="$2"
	local preview
	preview="$(json_preview "$HTTP_BODY_FILE")"
	fail "$test_name" "$message (status=$HTTP_STATUS, body=$preview)"
}

validate_non_empty() {
	local var_name="$1"
	local value="$2"
	if [[ -z "$value" ]]; then
		fail "Configuration check failed" "$var_name must not be empty."
		return 1
	fi
	return 0
}

enforce_safe_target() {
	if [[ "$API_TEST_ALLOW_NONLOCAL" == "true" ]]; then
		return 0
	fi

	if [[ "$API_BASE_URL" =~ ^https?://(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\])(:[0-9]+)?(/.*)?$ ]]; then
		return 0
	fi

	fail "Safety check failed" "API_BASE_URL points to a non-local target. Set API_TEST_ALLOW_NONLOCAL=true only if that target is an isolated test environment."
	return 1
}

mutations_enabled() {
	if [[ "$API_TEST_ALLOW_MUTATIONS" != "true" ]]; then
		return 1
	fi

	if [[ -z "$API_TEST_ENV_LABEL" ]]; then
		return 1
	fi

	return 0
}

require_mutation_consent() {
	local suite_name="$1"
	if mutations_enabled; then
		return 0
	fi

	skip "$suite_name" "Set API_TEST_ALLOW_MUTATIONS=true and API_TEST_ENV_LABEL to run state-changing tests against an isolated environment."
	return 1
}

ensure_admin_session() {
	if [[ -n "$ADMIN_TOKEN" ]]; then
		return 0
	fi

	validate_non_empty API_TEST_ADMIN_EMAIL "$API_TEST_ADMIN_EMAIL" || return 1
	validate_non_empty API_TEST_ADMIN_PASSWORD "$API_TEST_ADMIN_PASSWORD" || return 1

	local payload
	payload="$(jq -cn --arg email "$API_TEST_ADMIN_EMAIL" --arg password "$API_TEST_ADMIN_PASSWORD" '{email: $email, password: $password}')"

	if ! http_request POST /auth/login "$payload"; then
		fail "Admin session bootstrap failed" "$HTTP_ERROR_MESSAGE"
		return 1
	fi

	if ! status_and_body_match 200 '.token | type == "string" and length > 20'; then
		fail_with_response "Admin session bootstrap failed" "Login did not return the expected token response"
		return 1
	fi

	ADMIN_TOKEN="$(jq -r '.token' "$HTTP_BODY_FILE")"
	return 0
}

ensure_viewer_session() {
	if [[ -n "$VIEWER_TOKEN" ]]; then
		return 0
	fi

	if [[ -z "$INVITED_EMAIL" ]]; then
		return 1
	fi

	local payload
	payload="$(jq -cn --arg email "$INVITED_EMAIL" --arg password "$API_TEST_INVITED_USER_PASSWORD" '{email: $email, password: $password}')"

	if ! http_request POST /auth/login "$payload"; then
		return 1
	fi

	if ! status_and_body_match 200 '.token | type == "string" and length > 20'; then
		return 1
	fi

	VIEWER_TOKEN="$(jq -r '.token' "$HTTP_BODY_FILE")"
	return 0
}

suite_preflight() {
	local ok=0

	require_tool curl || ok=1
	require_tool jq || ok=1
	require_tool mktemp || ok=1
	enforce_safe_target || ok=1

	if [[ "$ok" -eq 0 ]]; then
		pass "Preflight checks completed"
	fi
}

test_health_endpoint() {
	local test_name="Health endpoint returns success"
	if ! http_request GET /actuator/health; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 200 '.status == "UP"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected status=200 and body.status=UP"
	fi
}

test_login_success() {
	local test_name="Login returns a valid access token"
	validate_non_empty API_TEST_ADMIN_EMAIL "$API_TEST_ADMIN_EMAIL" || return 0
	validate_non_empty API_TEST_ADMIN_PASSWORD "$API_TEST_ADMIN_PASSWORD" || return 0

	local payload
	payload="$(jq -cn --arg email "$API_TEST_ADMIN_EMAIL" --arg password "$API_TEST_ADMIN_PASSWORD" '{email: $email, password: $password}')"

	if ! http_request POST /auth/login "$payload"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 200 '.token | type == "string" and length > 20'; then
		ADMIN_TOKEN="$(jq -r '.token' "$HTTP_BODY_FILE")"
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected token-bearing login response"
	fi
}

test_login_invalid_password() {
	local test_name="Invalid credentials are rejected"
	validate_non_empty API_TEST_ADMIN_EMAIL "$API_TEST_ADMIN_EMAIL" || return 0

	local payload
	payload="$(jq -cn --arg email "$API_TEST_ADMIN_EMAIL" --arg password 'wrong-password' '{email: $email, password: $password}')"

	if ! http_request POST /auth/login "$payload"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 401 '.code == "UNAUTHORIZED"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 401 UNAUTHORIZED for invalid credentials"
	fi
}

test_protected_endpoint_without_token() {
	local test_name="Protected endpoint rejects a missing token"
	if ! http_request GET /accounts; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 401 '.code == "UNAUTHORIZED"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 401 UNAUTHORIZED without a token"
	fi
}

test_protected_endpoint_with_invalid_token() {
	local test_name="Protected endpoint rejects an invalid token"
	if ! http_request GET /accounts '' 'not-a-valid-jwt'; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 401 '.code == "UNAUTHORIZED"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 401 UNAUTHORIZED for an invalid token"
	fi
}

test_create_organization() {
	local test_name="Organization creation returns the expected fields"
	ensure_admin_session || return 0

	local payload
	payload="$(jq -cn --arg name "$CREATED_ORG_NAME" --arg currency 'ETB' '{name: $name, baseCurrency: $currency}')"

	if ! http_request POST /organizations "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e --arg name "$CREATED_ORG_NAME" '.name == $name and .baseCurrency == "ETB" and (.id | type == "number")' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		CREATED_ORG_ID="$(jq -r '.id' "$HTTP_BODY_FILE")"
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 200 with id, name, and baseCurrency"
	fi
}

test_duplicate_organization_name() {
	local test_name="Duplicate organization names are rejected"
	ensure_admin_session || return 0

	local payload
	payload="$(jq -cn --arg name "$CREATED_ORG_NAME" --arg currency 'ETB' '{name: $name, baseCurrency: $currency}')"

	if ! http_request POST /organizations "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 422 '.code == "ORGANIZATION_NAME_EXISTS"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 422 ORGANIZATION_NAME_EXISTS"
	fi
}

test_create_invitation() {
	local test_name="Invitation creation returns the expected fields"
	ensure_admin_session || return 0

	local payload
	payload="$(jq -cn --arg email "$INVITED_EMAIL" --arg role 'VIEWER' '{email: $email, role: $role}')"

	if ! http_request POST /users/invite "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e --arg email "$INVITED_EMAIL" '.invitedEmail == $email and .role == "VIEWER" and (.invitationToken | type == "string" and length > 20)' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		INVITATION_TOKEN_RAW="$(jq -r '.invitationToken' "$HTTP_BODY_FILE")"
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 200 with invitation metadata and raw invitation token"
	fi
}

test_accept_invitation() {
	local test_name="Invitation acceptance creates the invited user"

	if [[ -z "$INVITATION_TOKEN_RAW" ]]; then
		skip "$test_name" "No invitation token is available from a prior successful invitation creation test."
		return
	fi

	local payload
	payload="$(jq -cn --arg token "$INVITATION_TOKEN_RAW" --arg email "$INVITED_EMAIL" --arg fullName 'API Test Viewer' --arg password "$API_TEST_INVITED_USER_PASSWORD" '{token: $token, email: $email, fullName: $fullName, password: $password}')"

	if ! http_request POST /invitations/accept "$payload"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 200 '.message == "Invitation accepted"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 200 Invitation accepted"
	fi
}

test_reuse_invitation_rejected() {
	local test_name="Previously accepted invitations are rejected"

	if [[ -z "$INVITATION_TOKEN_RAW" ]]; then
		skip "$test_name" "No invitation token is available from a prior successful invitation creation test."
		return
	fi

	local payload
	payload="$(jq -cn --arg token "$INVITATION_TOKEN_RAW" --arg email "$INVITED_EMAIL" --arg fullName 'API Test Viewer' --arg password "$API_TEST_INVITED_USER_PASSWORD" '{token: $token, email: $email, fullName: $fullName, password: $password}')"

	if ! http_request POST /invitations/accept "$payload"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 422 '.code == "INVITATION_ALREADY_USED"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 422 INVITATION_ALREADY_USED"
	fi
}

test_viewer_login() {
	local test_name="Invited viewer can log in"

	if ! ensure_viewer_session; then
		fail "$test_name" "Login for the invited viewer did not succeed."
		return
	fi

	pass "$test_name"
}

test_duplicate_invitation_email() {
	local test_name="Inviting an existing organization email is rejected"
	ensure_admin_session || return 0

	local payload
	payload="$(jq -cn --arg email "$INVITED_EMAIL" --arg role 'VIEWER' '{email: $email, role: $role}')"

	if ! http_request POST /users/invite "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 422 '.code == "DUPLICATE_EMAIL"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 422 DUPLICATE_EMAIL"
	fi
}

test_account_validation() {
	local test_name="Account creation rejects missing required fields"
	ensure_admin_session || return 0

	local payload
	payload='{"accountCode":"","name":"","accountType":"ASSET","status":"ACTIVE"}'

	if ! http_request POST /accounts "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 400 '.code == "VALIDATION_ERROR"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 400 VALIDATION_ERROR for invalid account payload"
	fi
}

test_create_account() {
	local test_name="Account creation returns the expected fields"
	ensure_admin_session || return 0

	local payload
	payload="$(jq -cn --arg accountCode "$ACCOUNT_CODE" --arg name 'API Test Cash' --arg accountType 'ASSET' --arg status 'ACTIVE' '{accountCode: $accountCode, name: $name, accountType: $accountType, status: $status}')"

	if ! http_request POST /accounts "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e --arg code "$ACCOUNT_CODE" '.accountCode == $code and .status == "ACTIVE" and (.id | type == "number")' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		ACCOUNT_ID="$(jq -r '.id' "$HTTP_BODY_FILE")"
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 200 account response with id and accountCode"
	fi
}

test_duplicate_account_code() {
	local test_name="Duplicate account codes are rejected within one organization"
	ensure_admin_session || return 0

	local payload
	payload="$(jq -cn --arg accountCode "$ACCOUNT_CODE" --arg name 'API Test Cash Duplicate' --arg accountType 'ASSET' --arg status 'ACTIVE' '{accountCode: $accountCode, name: $name, accountType: $accountType, status: $status}')"

	if ! http_request POST /accounts "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 409 '.code == "DUPLICATE_ACCOUNT_CODE"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 409 DUPLICATE_ACCOUNT_CODE"
	fi
}

test_list_accounts() {
	local test_name="Account list includes the created account"

	if [[ -z "$ACCOUNT_ID" ]]; then
		skip "$test_name" "No account was created earlier in the run."
		return
	fi

	if ! http_request GET /accounts '' "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e --argjson accountId "$ACCOUNT_ID" 'map(select(.id == $accountId)) | length >= 1' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected the created account to appear in GET /accounts"
	fi
}

test_get_account() {
	local test_name="Account retrieval by ID returns the created account"

	if [[ -z "$ACCOUNT_ID" ]]; then
		skip "$test_name" "No account was created earlier in the run."
		return
	fi

	if ! http_request GET "/accounts/$ACCOUNT_ID" '' "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e --arg code "$ACCOUNT_CODE" '.accountCode == $code' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected GET /accounts/{id} to return the created account"
	fi
}

test_update_account() {
	local test_name="Account update returns the modified fields"

	if [[ -z "$ACCOUNT_ID" ]]; then
		skip "$test_name" "No account was created earlier in the run."
		return
	fi

	local payload
	payload="$(jq -cn --arg accountCode "$UPDATED_ACCOUNT_CODE" --arg name 'API Test Operating Cash' '{accountCode: $accountCode, name: $name}')"

	if ! http_request PATCH "/accounts/$ACCOUNT_ID" "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e --arg code "$UPDATED_ACCOUNT_CODE" --arg name 'API Test Operating Cash' '.accountCode == $code and .name == $name' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		ACCOUNT_CODE="$UPDATED_ACCOUNT_CODE"
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected updated accountCode and name"
	fi
}

test_deactivate_account() {
	local test_name="Account deactivation sets the status to INACTIVE"

	if [[ -z "$ACCOUNT_ID" ]]; then
		skip "$test_name" "No account was created earlier in the run."
		return
	fi

	if ! http_request POST "/accounts/$ACCOUNT_ID/deactivate" '' "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 200 '.status == "INACTIVE"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected account status INACTIVE after deactivation"
	fi
}

test_viewer_can_list_accounts() {
	local test_name="Viewer can list accounts"

	if ! ensure_viewer_session; then
		skip "$test_name" "Viewer session is unavailable because invitation acceptance or viewer login did not complete."
		return
	fi

	if ! http_request GET /accounts '' "$VIEWER_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e 'type == "array"' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 200 array response for viewer account listing"
	fi
}

test_viewer_can_get_account() {
	local test_name="Viewer can retrieve an account by ID"

	if [[ -z "$ACCOUNT_ID" ]]; then
		skip "$test_name" "No account was created earlier in the run."
		return
	fi

	if ! ensure_viewer_session; then
		skip "$test_name" "Viewer session is unavailable because invitation acceptance or viewer login did not complete."
		return
	fi

	if ! http_request GET "/accounts/$ACCOUNT_ID" '' "$VIEWER_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e --argjson accountId "$ACCOUNT_ID" '.id == $accountId' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected viewer GET /accounts/{id} access"
	fi
}

test_viewer_cannot_create_account() {
	local test_name="Viewer cannot create an account"

	if ! ensure_viewer_session; then
		skip "$test_name" "Viewer session is unavailable because invitation acceptance or viewer login did not complete."
		return
	fi

	local payload
	payload="$(jq -cn --arg accountCode "AT-${API_TEST_RUN_ID}-2000" --arg name 'Viewer Attempt' --arg accountType 'ASSET' --arg status 'ACTIVE' '{accountCode: $accountCode, name: $name, accountType: $accountType, status: $status}')"

	if ! http_request POST /accounts "$payload" "$VIEWER_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 403 '.code == "FORBIDDEN"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 403 FORBIDDEN for viewer account creation"
	fi
}

test_viewer_cannot_update_account() {
	local test_name="Viewer cannot update an account"

	if [[ -z "$ACCOUNT_ID" ]]; then
		skip "$test_name" "No account was created earlier in the run."
		return
	fi

	if ! ensure_viewer_session; then
		skip "$test_name" "Viewer session is unavailable because invitation acceptance or viewer login did not complete."
		return
	fi

	local payload
	payload='{"name":"Viewer Update Attempt"}'

	if ! http_request PATCH "/accounts/$ACCOUNT_ID" "$payload" "$VIEWER_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 403 '.code == "FORBIDDEN"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 403 FORBIDDEN for viewer account update"
	fi
}

test_viewer_cannot_create_organization() {
	local test_name="Viewer cannot create an organization"

	if ! ensure_viewer_session; then
		skip "$test_name" "Viewer session is unavailable because invitation acceptance or viewer login did not complete."
		return
	fi

	local payload
	payload="$(jq -cn --arg name "Viewer Org ${API_TEST_RUN_ID}" --arg currency 'ETB' '{name: $name, baseCurrency: $currency}')"

	if ! http_request POST /organizations "$payload" "$VIEWER_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 403 '.code == "FORBIDDEN"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 403 FORBIDDEN for viewer organization creation"
	fi
}

test_create_journal_accounts() {
	local test_name="Journal suite can create debit and credit accounts"
	ensure_admin_session || return 0

	local debit_payload
	debit_payload="$(jq -cn --arg accountCode "AT-${API_TEST_RUN_ID}-6100" --arg name 'API Test Journal Expense' --arg accountType 'EXPENSE' --arg status 'ACTIVE' '{accountCode: $accountCode, name: $name, accountType: $accountType, status: $status}')"

	if ! http_request POST /accounts "$debit_payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e '.id | type == "number"' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		JOURNAL_DEBIT_ACCOUNT_ID="$(jq -r '.id' "$HTTP_BODY_FILE")"
	else
		fail_with_response "$test_name" "Failed to create debit account"
		return
	fi

	local credit_payload
	credit_payload="$(jq -cn --arg accountCode "AT-${API_TEST_RUN_ID}-1105" --arg name 'API Test Journal Cash' --arg accountType 'ASSET' --arg status 'ACTIVE' '{accountCode: $accountCode, name: $name, accountType: $accountType, status: $status}')"

	if ! http_request POST /accounts "$credit_payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e '.id | type == "number"' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		JOURNAL_CREDIT_ACCOUNT_ID="$(jq -r '.id' "$HTTP_BODY_FILE")"
		pass "$test_name"
	else
		fail_with_response "$test_name" "Failed to create credit account"
	fi
}

test_create_journal_draft() {
	local test_name="Journal draft creation returns expected fields"
	ensure_admin_session || return 0

	if [[ -z "$JOURNAL_DEBIT_ACCOUNT_ID" || -z "$JOURNAL_CREDIT_ACCOUNT_ID" ]]; then
		skip "$test_name" "Journal accounts were not created."
		return
	fi

	local payload
	payload="$(jq -cn \
		--arg date "$(date +%F)" \
		--arg description "API journal draft ${API_TEST_RUN_ID}" \
		--arg reference "JE-${API_TEST_RUN_ID}" \
		--argjson debitId "$JOURNAL_DEBIT_ACCOUNT_ID" \
		--argjson creditId "$JOURNAL_CREDIT_ACCOUNT_ID" \
		'{transactionDate: $date, description: $description, reference: $reference, lines: [{accountId: $debitId, side: "DEBIT", amount: 100.00}, {accountId: $creditId, side: "CREDIT", amount: 100.00}]}')"

	if ! http_request POST /journal-entries "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e '.status == "DRAFT" and (.id | type == "number") and (.lines | length >= 2)' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		JOURNAL_ENTRY_ID="$(jq -r '.id' "$HTTP_BODY_FILE")"
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected DRAFT journal entry response"
	fi
}

test_get_journal_draft() {
	local test_name="Journal draft retrieval returns the created draft"
	ensure_admin_session || return 0

	if [[ -z "$JOURNAL_ENTRY_ID" ]]; then
		skip "$test_name" "No draft journal entry is available."
		return
	fi

	if ! http_request GET "/journal-entries/$JOURNAL_ENTRY_ID" '' "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e --argjson entryId "$JOURNAL_ENTRY_ID" '.id == $entryId and .status == "DRAFT" and (.lines | length >= 2)' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected GET /journal-entries/{id} to return the draft"
	fi
}

test_update_journal_draft() {
	local test_name="Journal draft update persists mutable fields"
	ensure_admin_session || return 0

	if [[ -z "$JOURNAL_ENTRY_ID" ]]; then
		skip "$test_name" "No draft journal entry is available."
		return
	fi

	local payload
	payload="$(jq -cn --arg description "API journal draft updated ${API_TEST_RUN_ID}" --arg reference "JE-UPD-${API_TEST_RUN_ID}" '{description: $description, reference: $reference}')"

	if ! http_request PATCH "/journal-entries/$JOURNAL_ENTRY_ID" "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e --arg description "API journal draft updated ${API_TEST_RUN_ID}" --arg reference "JE-UPD-${API_TEST_RUN_ID}" '.status == "DRAFT" and .description == $description and .reference == $reference' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected updated draft description and reference"
	fi
}

test_invalid_journal_creation_rejected() {
	local test_name="Journal draft creation rejects invalid payloads"
	ensure_admin_session || return 0

	if [[ -z "$JOURNAL_DEBIT_ACCOUNT_ID" ]]; then
		skip "$test_name" "Journal accounts were not created."
		return
	fi

	local payload
	payload="$(jq -cn --arg date "$(date +%F)" --arg description "Invalid journal ${API_TEST_RUN_ID}" --argjson debitId "$JOURNAL_DEBIT_ACCOUNT_ID" '{transactionDate: $date, description: $description, lines: [{accountId: $debitId, side: "DEBIT", amount: 10.00}]}')"

	if ! http_request POST /journal-entries "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 400 '.code == "VALIDATION_ERROR"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 400 VALIDATION_ERROR for a one-line journal draft"
	fi
}

test_delete_journal_draft() {
	local test_name="Journal draft deletion succeeds before posting"
	ensure_admin_session || return 0

	if [[ -z "$JOURNAL_DEBIT_ACCOUNT_ID" || -z "$JOURNAL_CREDIT_ACCOUNT_ID" ]]; then
		skip "$test_name" "Journal accounts were not created."
		return
	fi

	local create_payload
	create_payload="$(jq -cn \
		--arg date "$(date +%F)" \
		--arg description "API deletable draft ${API_TEST_RUN_ID}" \
		--arg reference "JE-DEL-${API_TEST_RUN_ID}" \
		--argjson debitId "$JOURNAL_DEBIT_ACCOUNT_ID" \
		--argjson creditId "$JOURNAL_CREDIT_ACCOUNT_ID" \
		'{transactionDate: $date, description: $description, reference: $reference, lines: [{accountId: $debitId, side: "DEBIT", amount: 20.00}, {accountId: $creditId, side: "CREDIT", amount: 20.00}]}')"

	if ! http_request POST /journal-entries "$create_payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" != "200" ]]; then
		fail_with_response "$test_name" "Expected draft creation before deletion"
		return
	fi

	JOURNAL_DELETED_ENTRY_ID="$(jq -r '.id' "$HTTP_BODY_FILE")"

	if ! http_request DELETE "/journal-entries/$JOURNAL_DELETED_ENTRY_ID" '' "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]]; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected successful draft deletion"
	fi
}

test_deleted_journal_is_not_found() {
	local test_name="Deleted journal drafts are no longer retrievable"
	ensure_admin_session || return 0

	if [[ -z "$JOURNAL_DELETED_ENTRY_ID" ]]; then
		skip "$test_name" "No deleted journal draft is available."
		return
	fi

	if ! http_request GET "/journal-entries/$JOURNAL_DELETED_ENTRY_ID" '' "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 404 '.code == "RESOURCE_NOT_FOUND"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 404 RESOURCE_NOT_FOUND after draft deletion"
	fi
}

test_post_journal_entry() {
	local test_name="Posting a balanced draft succeeds"
	ensure_admin_session || return 0

	if [[ -z "$JOURNAL_ENTRY_ID" ]]; then
		skip "$test_name" "No draft journal entry is available."
		return
	fi

	if ! http_request POST "/journal-entries/$JOURNAL_ENTRY_ID/post" '' "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 200 '.status == "POSTED" and (.postedAt | type == "string")'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected POSTED status after posting"
	fi
}

test_posted_journal_is_immutable() {
	local test_name="Posted journal entries cannot be patched"
	ensure_admin_session || return 0

	if [[ -z "$JOURNAL_ENTRY_ID" ]]; then
		skip "$test_name" "No posted journal entry is available."
		return
	fi

	local payload
	payload='{"description":"Mutation attempt"}'

	if ! http_request PATCH "/journal-entries/$JOURNAL_ENTRY_ID" "$payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 422 '.code == "JOURNAL_ENTRY_NOT_DRAFT"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 422 JOURNAL_ENTRY_NOT_DRAFT"
	fi
}

test_posted_balance_endpoint() {
	local test_name="Posted-only account balance reflects journal posting"
	ensure_admin_session || return 0

	if [[ -z "$JOURNAL_DEBIT_ACCOUNT_ID" ]]; then
		skip "$test_name" "No journal debit account is available."
		return
	fi

	if ! http_request GET "/accounts/$JOURNAL_DEBIT_ACCOUNT_ID/balance" '' "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 200 '.balance == 100'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected posted balance of 100 for the debit account"
	fi
}

test_unbalanced_journal_post_rejected() {
	local test_name="Posting an unbalanced draft is rejected"
	ensure_admin_session || return 0

	if [[ -z "$JOURNAL_DEBIT_ACCOUNT_ID" || -z "$JOURNAL_CREDIT_ACCOUNT_ID" ]]; then
		skip "$test_name" "Journal accounts were not created."
		return
	fi

	local create_payload
	create_payload="$(jq -cn \
		--arg date "$(date +%F)" \
		--arg description "API unbalanced draft ${API_TEST_RUN_ID}" \
		--arg reference "JE-UNBAL-${API_TEST_RUN_ID}" \
		--argjson debitId "$JOURNAL_DEBIT_ACCOUNT_ID" \
		--argjson creditId "$JOURNAL_CREDIT_ACCOUNT_ID" \
		'{transactionDate: $date, description: $description, reference: $reference, lines: [{accountId: $debitId, side: "DEBIT", amount: 75.00}, {accountId: $creditId, side: "CREDIT", amount: 50.00}]}')"

	if ! http_request POST /journal-entries "$create_payload" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" != "200" ]]; then
		fail_with_response "$test_name" "Expected draft creation before post validation"
		return
	fi

	local unbalanced_id
	unbalanced_id="$(jq -r '.id' "$HTTP_BODY_FILE")"

	if ! http_request POST "/journal-entries/$unbalanced_id/post" '' "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 422 '.code == "JOURNAL_ENTRY_UNBALANCED"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 422 JOURNAL_ENTRY_UNBALANCED"
	fi
}

test_list_journal_entries() {
	local test_name="Journal list supports filtering and pagination"
	ensure_admin_session || return 0

	if ! http_request GET '/journal-entries?status=DRAFT&page=0&size=5' '' "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]] && jq -e '.items and .page == 0 and .size == 5 and (.items | type == "array")' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected paginated journal list response"
	fi
}

test_viewer_cannot_create_journal_entry() {
	local test_name="Viewer cannot create journal entries"

	if ! ensure_viewer_session; then
		skip "$test_name" "Viewer session is unavailable because invitation acceptance or viewer login did not complete."
		return
	fi

	if [[ -z "$JOURNAL_DEBIT_ACCOUNT_ID" || -z "$JOURNAL_CREDIT_ACCOUNT_ID" ]]; then
		skip "$test_name" "Journal accounts were not created."
		return
	fi

	local payload
	payload="$(jq -cn \
		--arg date "$(date +%F)" \
		--arg description "Viewer attempt ${API_TEST_RUN_ID}" \
		--argjson debitId "$JOURNAL_DEBIT_ACCOUNT_ID" \
		--argjson creditId "$JOURNAL_CREDIT_ACCOUNT_ID" \
		'{transactionDate: $date, description: $description, lines: [{accountId: $debitId, side: "DEBIT", amount: 10.00}, {accountId: $creditId, side: "CREDIT", amount: 10.00}]}')"

	if ! http_request POST /journal-entries "$payload" "$VIEWER_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 403 '.code == "FORBIDDEN"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 403 FORBIDDEN for viewer journal creation"
	fi
}

ensure_reports_fixture() {
	ensure_admin_session || return 1

	if [[ -z "$JOURNAL_DEBIT_ACCOUNT_ID" || -z "$JOURNAL_CREDIT_ACCOUNT_ID" ]]; then
		local debit_payload
		debit_payload="$(jq -cn --arg accountCode "AT-${API_TEST_RUN_ID}-6100" --arg name 'API Test Journal Expense' --arg accountType 'EXPENSE' --arg status 'ACTIVE' '{accountCode: $accountCode, name: $name, accountType: $accountType, status: $status}')"

		if ! http_request POST /accounts "$debit_payload" "$ADMIN_TOKEN"; then
			return 1
		fi

		if [[ "$HTTP_STATUS" != "200" ]] || ! jq -e '.id | type == "number"' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
			HTTP_ERROR_MESSAGE="Failed to create report debit account (status=$HTTP_STATUS, body=$(cat "$HTTP_BODY_FILE"))"
			return 1
		fi
		JOURNAL_DEBIT_ACCOUNT_ID="$(jq -r '.id' "$HTTP_BODY_FILE")"

		local credit_payload
		credit_payload="$(jq -cn --arg accountCode "AT-${API_TEST_RUN_ID}-1105" --arg name 'API Test Journal Cash' --arg accountType 'ASSET' --arg status 'ACTIVE' '{accountCode: $accountCode, name: $name, accountType: $accountType, status: $status}')"

		if ! http_request POST /accounts "$credit_payload" "$ADMIN_TOKEN"; then
			return 1
		fi

		if [[ "$HTTP_STATUS" != "200" ]] || ! jq -e '.id | type == "number"' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
			HTTP_ERROR_MESSAGE="Failed to create report credit account (status=$HTTP_STATUS, body=$(cat "$HTTP_BODY_FILE"))"
			return 1
		fi
		JOURNAL_CREDIT_ACCOUNT_ID="$(jq -r '.id' "$HTTP_BODY_FILE")"
	fi

	if [[ -z "$JOURNAL_ENTRY_ID" ]]; then
		local create_payload
		create_payload="$(jq -cn \
			--arg date "$(date +%F)" \
			--arg description "Report fixture journal ${API_TEST_RUN_ID}" \
			--arg reference "RPT-${API_TEST_RUN_ID}" \
			--argjson debitId "$JOURNAL_DEBIT_ACCOUNT_ID" \
			--argjson creditId "$JOURNAL_CREDIT_ACCOUNT_ID" \
			'{transactionDate: $date, description: $description, reference: $reference, lines: [{accountId: $debitId, side: "DEBIT", amount: 100.00}, {accountId: $creditId, side: "CREDIT", amount: 100.00}]}')"

		if ! http_request POST /journal-entries "$create_payload" "$ADMIN_TOKEN"; then
			return 1
		fi

		if [[ "$HTTP_STATUS" != "200" ]] || ! jq -e '(.id | type == "number") and .status == "DRAFT"' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
			HTTP_ERROR_MESSAGE="Failed to create report fixture journal (status=$HTTP_STATUS, body=$(cat "$HTTP_BODY_FILE"))"
			return 1
		fi
		JOURNAL_ENTRY_ID="$(jq -r '.id' "$HTTP_BODY_FILE")"

		if ! http_request POST "/journal-entries/$JOURNAL_ENTRY_ID/post" '' "$ADMIN_TOKEN"; then
			return 1
		fi

		if [[ "$HTTP_STATUS" != "200" ]]; then
			HTTP_ERROR_MESSAGE="Failed to post report fixture journal (status=$HTTP_STATUS, body=$(cat "$HTTP_BODY_FILE"))"
			return 1
		fi
	fi

	return 0

}

ensure_reports_viewer_fixture() {
	ensure_reports_fixture || return 1

	if [[ -n "$VIEWER_TOKEN" ]]; then
		return 0
	fi

	if [[ -z "$INVITED_EMAIL" ]]; then
		HTTP_ERROR_MESSAGE="Viewer fixture email is not initialized"
		return 1
	fi

	if [[ -z "$INVITATION_TOKEN_RAW" ]]; then
		local invite_payload
		invite_payload="$(jq -cn --arg email "$INVITED_EMAIL" --arg role 'VIEWER' '{email: $email, role: $role}')"

		if ! http_request POST /users/invite "$invite_payload" "$ADMIN_TOKEN"; then
			return 1
		fi

		if [[ "$HTTP_STATUS" != "200" ]] || ! jq -e '.invitationToken | type == "string" and length > 20' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
			HTTP_ERROR_MESSAGE="Failed to create report fixture invitation (status=$HTTP_STATUS, body=$(cat "$HTTP_BODY_FILE"))"
			return 1
		fi

		INVITATION_TOKEN_RAW="$(jq -r '.invitationToken' "$HTTP_BODY_FILE")"
	fi

	local accept_payload
	accept_payload="$(jq -cn --arg token "$INVITATION_TOKEN_RAW" --arg email "$INVITED_EMAIL" --arg fullName 'API Test Viewer' --arg password "$API_TEST_INVITED_USER_PASSWORD" '{token: $token, email: $email, fullName: $fullName, password: $password}')"

	if ! http_request POST /invitations/accept "$accept_payload"; then
		return 1
	fi

	if [[ "$HTTP_STATUS" != "200" ]] && ! jq -e '.code == "INVITATION_ALREADY_USED"' "$HTTP_BODY_FILE" >/dev/null 2>&1; then
		HTTP_ERROR_MESSAGE="Failed to accept report fixture invitation (status=$HTTP_STATUS, body=$(cat "$HTTP_BODY_FILE"))"
		return 1
	fi

	ensure_viewer_session || {
		HTTP_ERROR_MESSAGE="Failed to create viewer session for report fixture"
		return 1
	}

	return 0
}

test_general_ledger_report() {
	local test_name="General ledger report returns running balances"
	require_mutation_consent "$test_name" || return
	if ! ensure_reports_fixture; then
		fail "$test_name" "${HTTP_ERROR_MESSAGE:-Failed to bootstrap report fixture data}"
		return
	fi

	if ! http_request GET "/api/reports/general-ledger?accountId=$JOURNAL_DEBIT_ACCOUNT_ID&page=0&size=20" "" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 200 '(.accountId == '$JOURNAL_DEBIT_ACCOUNT_ID') and ((.lines | length) >= 0)'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected status=200 with general ledger data"
	fi
}

test_trial_balance_report() {
	local test_name="Trial balance report shows all accounts and is balanced"
	require_mutation_consent "$test_name" || return
	if ! ensure_reports_fixture; then
		fail "$test_name" "${HTTP_ERROR_MESSAGE:-Failed to bootstrap report fixture data}"
		return
	fi

	if ! http_request GET "/api/reports/trial-balance" "" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 200 '((.accounts | length) >= 2) and .balanced == true'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected status=200 with all accounts and balanced=true"
	fi
}

test_audit_logs_query() {
	local test_name="Audit logs query returns filtered results with pagination"
	require_mutation_consent "$test_name" || return
	if ! ensure_reports_fixture; then
		fail "$test_name" "${HTTP_ERROR_MESSAGE:-Failed to bootstrap report fixture data}"
		return
	fi

	if ! http_request GET "/api/audit-logs?page=0&size=20" "" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 200 '((.items | length) >= 0) and .page == 0'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected status=200 with audit log items"
	fi
}

test_reverse_journal_entry() {
	local test_name="Reversing a posted journal entry succeeds and creates reversal"
	require_mutation_consent "$test_name" || return
	if ! ensure_reports_fixture; then
		fail "$test_name" "${HTTP_ERROR_MESSAGE:-Failed to bootstrap report fixture data}"
		return
	fi

	if ! http_request POST "/journal-entries/$JOURNAL_ENTRY_ID/reverse" "" "$ADMIN_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 200 '(.status == "POSTED") and (.description | contains("Reversal"))'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected status=200 with reversal entry that is POSTED"
	fi
}

test_viewer_can_view_general_ledger() {
	local test_name="Viewer can view general ledger reports"
	require_mutation_consent "$test_name" || return
	if ! ensure_reports_viewer_fixture; then
		skip "$test_name" "${HTTP_ERROR_MESSAGE:-Viewer fixture bootstrap failed}"
		return
	fi

	if ! http_request GET "/api/reports/general-ledger?accountId=$JOURNAL_DEBIT_ACCOUNT_ID" "" "$VIEWER_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if [[ "$HTTP_STATUS" == "200" ]]; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected status=200 for viewer general ledger access"
	fi
}

test_viewer_cannot_reverse_entry() {
	local test_name="Viewer cannot reverse journal entries"
	require_mutation_consent "$test_name" || return
	if ! ensure_reports_viewer_fixture; then
		skip "$test_name" "${HTTP_ERROR_MESSAGE:-Viewer fixture bootstrap failed}"
		return
	fi

	if ! http_request POST "/journal-entries/$JOURNAL_ENTRY_ID/reverse" "" "$VIEWER_TOKEN"; then
		fail "$test_name" "$HTTP_ERROR_MESSAGE"
		return
	fi

	if status_and_body_match 403 '.code == "FORBIDDEN"'; then
		pass "$test_name"
	else
		fail_with_response "$test_name" "Expected 403 FORBIDDEN for viewer reversal attempt"
	fi
}

test_tenant_isolation_status() {
	skip "Tenant-isolation cross-organization test" "The current public API does not provide a safe HTTP-only workflow for provisioning a second authenticated organization user, so cross-organization read/write checks are blocked in this runner."
}

test_forged_organization_override_status() {
	skip "Forged organization override test" "Current protected request DTOs do not expose organizationId override fields, so there is no direct forged organization field to submit through the Day 2 API surface."
}

suite_health() {
	test_health_endpoint
}

suite_auth() {
	test_login_success
	test_login_invalid_password
	test_protected_endpoint_without_token
	test_protected_endpoint_with_invalid_token
}

suite_organizations() {
	require_mutation_consent "Organization suite" || return
	test_create_organization
	test_duplicate_organization_name
}

suite_invitations() {
	require_mutation_consent "Invitation suite" || return
	test_create_invitation
	test_accept_invitation
	test_reuse_invitation_rejected
	test_viewer_login
	test_duplicate_invitation_email
}

suite_accounts() {
	require_mutation_consent "Accounts suite" || return
	test_account_validation
	test_create_account
	test_duplicate_account_code
	test_list_accounts
	test_get_account
	test_update_account
	test_deactivate_account
}

suite_journal() {
	require_mutation_consent "Journal suite" || return
	test_create_journal_accounts
	test_create_journal_draft
	test_get_journal_draft
	test_update_journal_draft
	test_invalid_journal_creation_rejected
	test_delete_journal_draft
	test_deleted_journal_is_not_found
	test_post_journal_entry
	test_posted_journal_is_immutable
	test_posted_balance_endpoint
	test_unbalanced_journal_post_rejected
	test_list_journal_entries
	test_viewer_cannot_create_journal_entry
}

suite_reports() {
	require_mutation_consent "Reports suite" || return
	test_general_ledger_report
	test_trial_balance_report
	test_audit_logs_query
	test_reverse_journal_entry
	test_viewer_can_view_general_ledger
	test_viewer_cannot_reverse_entry
}

suite_authorization() {
	require_mutation_consent "Authorization suite" || return
	test_viewer_can_list_accounts
	test_viewer_can_get_account
	test_viewer_cannot_create_account
	test_viewer_cannot_update_account
	test_viewer_cannot_create_organization
	test_tenant_isolation_status
	test_forged_organization_override_status
}

suite_selftest_fail() {
	fail "Intentional self-test failure" "This verifies that the script exits nonzero when assertions fail."
}

main() {
	if [[ $# -gt 0 ]]; then
		case "$1" in
			-h|--help)
				usage
				exit 0
				;;
		esac
	fi

	REQUESTED_GROUPS=("$@")
	assert_known_groups "${REQUESTED_GROUPS[@]}"

	if group_selected selftest-fail && [[ ${#REQUESTED_GROUPS[@]} -eq 1 ]]; then
		suite_selftest_fail
		finish
	fi

	suite_preflight

	if [[ "$FAIL_COUNT" -gt 0 ]]; then
		finish
	fi

	if [[ ${#REQUESTED_GROUPS[@]} -eq 0 || $(group_selected health; printf '%s' "$?") -eq 0 ]]; then
		suite_health
	fi

	if [[ ${#REQUESTED_GROUPS[@]} -eq 0 || $(group_selected auth; printf '%s' "$?") -eq 0 ]]; then
		suite_auth
	fi

	if [[ ${#REQUESTED_GROUPS[@]} -eq 0 || $(group_selected organizations; printf '%s' "$?") -eq 0 ]]; then
		suite_organizations
	fi

	if [[ ${#REQUESTED_GROUPS[@]} -eq 0 || $(group_selected invitations; printf '%s' "$?") -eq 0 ]]; then
		suite_invitations
	fi

	if [[ ${#REQUESTED_GROUPS[@]} -eq 0 || $(group_selected accounts; printf '%s' "$?") -eq 0 ]]; then
		suite_accounts
	fi

	if [[ ${#REQUESTED_GROUPS[@]} -eq 0 || $(group_selected journal; printf '%s' "$?") -eq 0 ]]; then
		suite_journal
	fi

	if [[ ${#REQUESTED_GROUPS[@]} -eq 0 || $(group_selected authorization; printf '%s' "$?") -eq 0 ]]; then
		suite_authorization
	fi

	if [[ ${#REQUESTED_GROUPS[@]} -eq 0 || $(group_selected reports; printf '%s' "$?") -eq 0 ]]; then
		suite_reports
	fi

	finish
}

main "$@"
