[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') {
    & powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File $PSCommandPath
    exit $LASTEXITCODE
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Security

function Get-UiText {
    param([Parameter(Mandatory = $true)][string]$EscapedJsonString)
    return ConvertFrom-Json $EscapedJsonString
}

function Protect-Secret {
    param([Parameter(Mandatory = $true)][string]$Value)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    try {
        $protected = [Security.Cryptography.ProtectedData]::Protect(
            $bytes,
            $null,
            [Security.Cryptography.DataProtectionScope]::CurrentUser
        )
        return [Convert]::ToBase64String($protected)
    }
    finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Unprotect-Secret {
    param([Parameter(Mandatory = $true)][string]$CipherText)

    $protected = [Convert]::FromBase64String($CipherText)
    $plain = $null
    try {
        $plain = [Security.Cryptography.ProtectedData]::Unprotect(
            $protected,
            $null,
            [Security.Cryptography.DataProtectionScope]::CurrentUser
        )
        return [Text.Encoding]::UTF8.GetString($plain)
    }
    finally {
        [Array]::Clear($protected, 0, $protected.Length)
        if ($null -ne $plain) {
            [Array]::Clear($plain, 0, $plain.Length)
        }
    }
}

function Get-LastFour {
    param([Parameter(Mandatory = $true)][string]$Value)

    if ($Value.Length -le 4) {
        return $Value
    }
    return $Value.Substring($Value.Length - 4)
}

$localAppData = [Environment]::GetFolderPath('LocalApplicationData')
$secretDirectory = Join-Path $localAppData 'A12TeachingAgent\secrets'
$secretPath = Join-Path $secretDirectory 'kimi-api-keys.dpapi'
$existingKeys = @('', '', '')
$existingActiveIndex = 1

if (Test-Path -LiteralPath $secretPath -PathType Leaf) {
    try {
        $existing = Get-Content -LiteralPath $secretPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ([int]$existing.activeKeyIndex -ge 1 -and [int]$existing.activeKeyIndex -le 3) {
            $existingActiveIndex = [int]$existing.activeKeyIndex
        }
        foreach ($entry in @($existing.keys)) {
            $slot = [int]$entry.slot
            if ($slot -ge 1 -and $slot -le 3) {
                $existingKeys[$slot - 1] = Unprotect-Secret -CipherText ([string]$entry.cipherText)
            }
        }
    }
    catch {
        [Windows.Forms.MessageBox]::Show(
            (Get-UiText '"\u65e0\u6cd5\u8bfb\u53d6\u73b0\u6709\u5bc6\u94a5\u914d\u7f6e\u3002\u8bf7\u91cd\u65b0\u8f93\u5165\u4e09\u4e2a\u5bc6\u94a5\u3002"'),
            (Get-UiText '"A12 Kimi API Key \u8bbe\u7f6e"'),
            [Windows.Forms.MessageBoxButtons]::OK,
            [Windows.Forms.MessageBoxIcon]::Warning
        ) | Out-Null
        $existingKeys = @('', '', '')
        $existingActiveIndex = 1
    }
}

$form = New-Object Windows.Forms.Form
$form.Text = Get-UiText '"A12 Kimi API Key \u8bbe\u7f6e"'
$form.StartPosition = [Windows.Forms.FormStartPosition]::CenterScreen
$form.ClientSize = [Drawing.Size]::new(620, 390)
$form.FormBorderStyle = [Windows.Forms.FormBorderStyle]::FixedDialog
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.TopMost = $true
$form.Font = New-Object Drawing.Font('Microsoft YaHei UI', 10)

$heading = New-Object Windows.Forms.Label
$heading.Location = [Drawing.Point]::new(30, 24)
$heading.Size = [Drawing.Size]::new(560, 32)
$heading.Font = New-Object Drawing.Font('Microsoft YaHei UI', 16, [Drawing.FontStyle]::Bold)
$heading.Text = Get-UiText '"\u5b89\u5168\u914d\u7f6e Kimi API Key"'
$form.Controls.Add($heading)

$description = New-Object Windows.Forms.Label
$description.Location = [Drawing.Point]::new(32, 62)
$description.Size = [Drawing.Size]::new(550, 42)
$description.ForeColor = [Drawing.Color]::FromArgb(80, 90, 110)
$description.Text = Get-UiText '"\u5bc6\u94a5\u5c06\u4f7f\u7528 Windows DPAPI \u6309\u5f53\u524d\u7528\u6237\u52a0\u5bc6\uff0c\u4e0d\u4f1a\u5199\u5165\u9879\u76ee\u6216 Git\u3002"'
$form.Controls.Add($description)

$textBoxes = @()
$radioButtons = @()

for ($index = 0; $index -lt 3; $index++) {
    $rowY = 120 + ($index * 62)

    $label = New-Object Windows.Forms.Label
    $label.Location = [Drawing.Point]::new(34, ($rowY + 7))
    $label.Size = [Drawing.Size]::new(125, 26)
    if ($index -eq 0) {
        $label.Text = Get-UiText '"\u4e3b\u5bc6\u94a5 Key 1"'
    }
    else {
        $label.Text = (Get-UiText '"\u5907\u7528\u5bc6\u94a5 Key {0}"') -f ($index + 1)
    }
    $form.Controls.Add($label)

    $textBox = New-Object Windows.Forms.TextBox
    $textBox.Location = [Drawing.Point]::new(165, $rowY)
    $textBox.Size = [Drawing.Size]::new(330, 30)
    $textBox.UseSystemPasswordChar = $true
    $textBox.Text = $existingKeys[$index]
    $form.Controls.Add($textBox)
    $textBoxes += $textBox

    $radio = New-Object Windows.Forms.RadioButton
    $radio.Location = [Drawing.Point]::new(510, ($rowY + 3))
    $radio.Size = [Drawing.Size]::new(82, 28)
    $radio.Text = Get-UiText '"\u5f53\u524d\u542f\u7528"'
    $radio.Checked = (($index + 1) -eq $existingActiveIndex)
    $form.Controls.Add($radio)
    $radioButtons += $radio
}

$securityNote = New-Object Windows.Forms.Label
$securityNote.Location = [Drawing.Point]::new(34, 306)
$securityNote.Size = [Drawing.Size]::new(370, 32)
$securityNote.ForeColor = [Drawing.Color]::FromArgb(80, 90, 110)
$securityNote.Text = Get-UiText '"\u4e09\u4e2a\u5bc6\u94a5\u5fc5\u987b\u5168\u90e8\u586b\u5199\u4e14\u4e92\u4e0d\u76f8\u540c\u3002"'
$form.Controls.Add($securityNote)

$saveButton = New-Object Windows.Forms.Button
$saveButton.Location = [Drawing.Point]::new(410, 300)
$saveButton.Size = [Drawing.Size]::new(112, 40)
$saveButton.Text = Get-UiText '"\u4fdd\u5b58\u5e76\u542f\u7528"'
$saveButton.BackColor = [Drawing.Color]::FromArgb(86, 61, 255)
$saveButton.ForeColor = [Drawing.Color]::White
$saveButton.FlatStyle = [Windows.Forms.FlatStyle]::Flat
$form.Controls.Add($saveButton)

$cancelButton = New-Object Windows.Forms.Button
$cancelButton.Location = [Drawing.Point]::new(530, 300)
$cancelButton.Size = [Drawing.Size]::new(72, 40)
$cancelButton.Text = Get-UiText '"\u53d6\u6d88"'
$form.Controls.Add($cancelButton)

$script:Saved = $false
$script:ActiveIndex = 0
$script:LastFours = @()

$saveButton.Add_Click({
    $values = @(
        $textBoxes[0].Text.Trim(),
        $textBoxes[1].Text.Trim(),
        $textBoxes[2].Text.Trim()
    )

    if ($values | Where-Object { [string]::IsNullOrWhiteSpace($_) }) {
        [Windows.Forms.MessageBox]::Show(
            (Get-UiText '"\u8bf7\u586b\u5199\u5168\u90e8\u4e09\u4e2a API Key\u3002"'),
            $form.Text,
            [Windows.Forms.MessageBoxButtons]::OK,
            [Windows.Forms.MessageBoxIcon]::Warning
        ) | Out-Null
        return
    }

    $distinct = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($value in $values) {
        [void]$distinct.Add($value)
    }
    if ($distinct.Count -ne 3) {
        [Windows.Forms.MessageBox]::Show(
            (Get-UiText '"\u4e09\u4e2a API Key \u5fc5\u987b\u4e92\u4e0d\u76f8\u540c\u3002"'),
            $form.Text,
            [Windows.Forms.MessageBoxButtons]::OK,
            [Windows.Forms.MessageBoxIcon]::Warning
        ) | Out-Null
        return
    }

    $activeIndex = 1
    for ($index = 0; $index -lt $radioButtons.Count; $index++) {
        if ($radioButtons[$index].Checked) {
            $activeIndex = $index + 1
            break
        }
    }

    try {
        [IO.Directory]::CreateDirectory($secretDirectory) | Out-Null
        $keyEntries = @()
        for ($index = 0; $index -lt 3; $index++) {
            $keyEntries += [ordered]@{
                slot       = $index + 1
                lastFour   = Get-LastFour -Value $values[$index]
                cipherText = Protect-Secret -Value $values[$index]
            }
        }

        $payload = [ordered]@{
            version        = 1
            activeKeyIndex = $activeIndex
            savedAtUtc     = [DateTime]::UtcNow.ToString('o')
            keys           = $keyEntries
        }

        $temporaryPath = "$secretPath.$([Guid]::NewGuid().ToString('N')).tmp"
        try {
            $payload | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $temporaryPath -Encoding UTF8
            Move-Item -LiteralPath $temporaryPath -Destination $secretPath -Force
        }
        finally {
            if (Test-Path -LiteralPath $temporaryPath) {
                Remove-Item -LiteralPath $temporaryPath -Force
            }
        }

        $script:Saved = $true
        $script:ActiveIndex = $activeIndex
        $script:LastFours = @($keyEntries | ForEach-Object { [string]$_.lastFour })

        $safeSummary = @(
            (Get-UiText '"\u5df2\u4f7f\u7528 Windows DPAPI \u52a0\u5bc6\u4fdd\u5b58\u3002"'),
            '',
            ("Key 1: ****{0}" -f $script:LastFours[0]),
            ("Key 2: ****{0}" -f $script:LastFours[1]),
            ("Key 3: ****{0}" -f $script:LastFours[2]),
            '',
            ((Get-UiText '"\u5f53\u524d\u542f\u7528\uff1aKey {0}"') -f $script:ActiveIndex)
        ) -join [Environment]::NewLine

        [Windows.Forms.MessageBox]::Show(
            $safeSummary,
            $form.Text,
            [Windows.Forms.MessageBoxButtons]::OK,
            [Windows.Forms.MessageBoxIcon]::Information
        ) | Out-Null
        $form.Close()
    }
    catch {
        [Windows.Forms.MessageBox]::Show(
            (Get-UiText '"\u5bc6\u94a5\u4fdd\u5b58\u5931\u8d25\u3002\u672a\u5199\u5165\u9879\u76ee\u6216\u65e5\u5fd7\u3002"'),
            $form.Text,
            [Windows.Forms.MessageBoxButtons]::OK,
            [Windows.Forms.MessageBoxIcon]::Error
        ) | Out-Null
    }
    finally {
        for ($index = 0; $index -lt $values.Count; $index++) {
            $values[$index] = $null
        }
    }
})

$cancelButton.Add_Click({
    $form.Close()
})

$form.AcceptButton = $saveButton
$form.CancelButton = $cancelButton
$form.Add_Shown({ $textBoxes[0].Focus() })
[void]$form.ShowDialog()

for ($index = 0; $index -lt $existingKeys.Count; $index++) {
    $existingKeys[$index] = $null
    $textBoxes[$index].Text = ''
}

if (-not $script:Saved) {
    Write-Output 'A12_KIMI_KEYS_CANCELLED'
    exit 2
}

Write-Output 'A12_KIMI_KEYS_SAVED'
Write-Output ("ActiveKey={0}" -f $script:ActiveIndex)
for ($index = 0; $index -lt $script:LastFours.Count; $index++) {
    Write-Output ("Key{0}=****{1}" -f ($index + 1), $script:LastFours[$index])
}
