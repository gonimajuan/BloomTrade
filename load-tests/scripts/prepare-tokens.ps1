<#
.SYNOPSIS
  Genera load-tests\users.csv con tokens JWT válidos para los planes JMeter
  ESC-R1 y ESC-R2.

.DESCRIPTION
  Itera N usuarios de loadtest pre-existentes en BloomTrade, hace login + MFA
  programáticamente leyendo el OTP desde la API REST de MailHog (no requiere
  modificar el backend ni introducir endpoints especiales).

  Flujo por usuario:
    1) Limpia bandeja de MailHog para evitar leer OTPs viejos.
    2) POST /api/v1/auth/login  ->  tempSessionId
    3) GET  http://localhost:8025/api/v2/messages  ->  extrae OTP (6 dígitos)
    4) POST /api/v1/auth/mfa/verify  ->  accessToken
    5) Append a users.csv (userId, email, accessToken)

.PARAMETER BackendUrl
  URL base del backend BloomTrade (default http://localhost:8080).

.PARAMETER MailhogUrl
  URL base de la UI / API de MailHog (default http://localhost:8025).

.PARAMETER EmailPrefix
  Prefijo de email de los usuarios seed. Default "loadtest" produce
  loadtest+0001@bloomtrade.local hasta +NNNN@.

.PARAMETER EmailDomain
  Dominio del email seed. Default "bloomtrade.local".

.PARAMETER Password
  Password compartido de los usuarios seed (plaintext, será enviado por TLS).

.PARAMETER UserCount
  Cantidad de usuarios a procesar. Default 1500.

.PARAMETER OutputCsv
  Path del CSV de salida. Default .\users.csv (relativo al cwd).

.EXAMPLE
  .\scripts\prepare-tokens.ps1 -UserCount 10 -OutputCsv .\users.csv

.NOTES
  Pre-requisitos: ver load-tests\README.md §3.
  Si OTP TTL (5 min) se agota antes de terminar, bajar -UserCount o paralelizar
  por chunks (este script es secuencial por simplicidad).
#>

[CmdletBinding()]
param(
    [string] $BackendUrl   = "http://localhost:8080",
    [string] $MailhogUrl   = "http://localhost:8025",
    [string] $EmailPrefix  = "loadtest",
    [string] $EmailDomain  = "bloomtrade.local",
    [string] $Password     = "Loadtest!2026",
    [int]    $UserCount    = 1500,
    [string] $OutputCsv    = ".\users.csv"
)

$ErrorActionPreference = "Stop"

function Get-OtpFromMailhog {
    param(
        [Parameter(Mandatory)] [string] $To,
        [int] $TimeoutSec = 10
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $resp = Invoke-RestMethod -Uri "$MailhogUrl/api/v2/search?kind=to&query=$([uri]::EscapeDataString($To))" -Method Get
        if ($resp.items -and $resp.items.Count -gt 0) {
            $body = $resp.items[0].Content.Body
            $match = [regex]::Match($body, "\b\d{6}\b")
            if ($match.Success) {
                return $match.Value
            }
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Timeout esperando OTP para $To (revisa que MailHog esté capturando emails)."
}

function Clear-MailhogInbox {
    try {
        Invoke-RestMethod -Uri "$MailhogUrl/api/v1/messages" -Method Delete | Out-Null
    } catch {
        Write-Warning "No se pudo limpiar MailHog: $_"
    }
}

# Output CSV header
Set-Content -Path $OutputCsv -Value "userId,email,accessToken" -Encoding utf8

$succeeded = 0
$failed    = 0
$startedAt = Get-Date

for ($i = 1; $i -le $UserCount; $i++) {
    $email = "{0}+{1:0000}@{2}" -f $EmailPrefix, $i, $EmailDomain

    try {
        Clear-MailhogInbox

        $loginBody = @{ email = $email; password = $Password } | ConvertTo-Json -Compress
        $loginResp = Invoke-RestMethod `
            -Uri "$BackendUrl/api/v1/auth/login" `
            -Method Post `
            -ContentType "application/json" `
            -Body $loginBody

        $tempSessionId = $loginResp.tempSessionId
        if (-not $tempSessionId) {
            throw "Login no devolvió tempSessionId (response: $($loginResp | ConvertTo-Json -Compress))"
        }

        $otp = Get-OtpFromMailhog -To $email

        $mfaBody = @{ tempSessionId = $tempSessionId; code = $otp } | ConvertTo-Json -Compress
        $mfaResp = Invoke-RestMethod `
            -Uri "$BackendUrl/api/v1/auth/mfa/verify" `
            -Method Post `
            -ContentType "application/json" `
            -Body $mfaBody

        $accessToken = $mfaResp.accessToken
        $userId      = $mfaResp.user.id

        Add-Content -Path $OutputCsv -Value ("{0},{1},{2}" -f $userId, $email, $accessToken) -Encoding utf8

        $succeeded++
        if ($succeeded % 50 -eq 0) {
            $elapsed = ((Get-Date) - $startedAt).TotalSeconds
            Write-Output ("[{0:000}/{1:000}] OK — {2:N1}s acumulados ({3:N1} login/s)" -f $succeeded, $UserCount, $elapsed, ($succeeded / [Math]::Max($elapsed, 1)))
        }
    } catch {
        $failed++
        Write-Warning ("[$i] $email -> $($_.Exception.Message)")
        if ($failed -gt 10 -and $failed -ge ($succeeded / 2)) {
            throw "Tasa de fallo alta ($failed errores con $succeeded éxitos). Abortando."
        }
    }
}

$totalSec = ((Get-Date) - $startedAt).TotalSeconds
Write-Output ""
Write-Output "==================================================="
Write-Output "Resumen: $succeeded éxitos, $failed fallos en ${totalSec:N1}s"
Write-Output "Archivo generado: $OutputCsv"
Write-Output "==================================================="

if ($succeeded -eq 0) {
    exit 1
}
