Set-StrictMode -Version Latest

function ConvertTo-A12Array {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [System.Array]) {
        return @($Value)
    }
    return @($Value)
}

function ConvertTo-A12NormalizedText {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return ""
    }
    $text = ([string]$Value).Normalize([System.Text.NormalizationForm]::FormKC).ToLowerInvariant()
    return (($text -replace "\s+", " ").Trim())
}

function New-A12Preview {
    param(
        [AllowNull()][object]$Value,
        [int]$MaxLength = 120
    )

    $text = ([string]$Value) -replace "\s+", " "
    $text = $text.Trim()
    if ($text.Length -le $MaxLength) {
        return $text
    }
    if ($MaxLength -le 1) {
        return ""
    }
    if ($MaxLength -le 3) {
        return $text.Substring(0, $MaxLength)
    }
    return $text.Substring(0, $MaxLength - 3) + "..."
}

function Assert-A12EvalCase {
    param(
        [Parameter(Mandatory = $true)][object]$Case,
        [Parameter(Mandatory = $true)][int]$Index
    )

    foreach ($field in @("id", "question", "expectedAnchors", "expectedConcepts")) {
        if (-not ($Case.PSObject.Properties.Name -contains $field)) {
            throw "Eval case at index $Index is missing required field '$field'."
        }
    }
    if ([string]::IsNullOrWhiteSpace([string]$Case.id)) {
        throw "Eval case at index $Index has an empty id."
    }
    if ([string]::IsNullOrWhiteSpace([string]$Case.question)) {
        throw "Eval case '$($Case.id)' has an empty question."
    }
    $anchors = @(ConvertTo-A12Array $Case.expectedAnchors | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    $concepts = @(ConvertTo-A12Array $Case.expectedConcepts | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    if ($anchors.Count -eq 0 -and $concepts.Count -eq 0) {
        throw "Eval case '$($Case.id)' must include at least one expected anchor or concept."
    }
    foreach ($forbidden in @("expectedText", "answer", "goldChunkText", "fullText", "sourceText")) {
        if ($Case.PSObject.Properties.Name -contains $forbidden) {
            throw "Eval case '$($Case.id)' contains forbidden long-text-like field '$forbidden'."
        }
    }
}

function Read-A12EvalCases {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Eval file not found: $Path"
    }
    $json = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    $parsed = $json | ConvertFrom-Json
    $cases = @($parsed)
    if ($cases.Count -eq 0) {
        throw "Eval file contains no cases: $Path"
    }
    for ($i = 0; $i -lt $cases.Count; $i++) {
        Assert-A12EvalCase -Case $cases[$i] -Index $i
    }
    return $cases
}

function Get-A12HitSearchText {
    param([Parameter(Mandatory = $true)][object]$Hit)

    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($field in @("title", "content", "contentPreview", "preview", "hitReason", "sourceFilename")) {
        if ($Hit.PSObject.Properties.Name -contains $field -and $null -ne $Hit.$field) {
            $parts.Add([string]$Hit.$field)
        }
    }
    if ($Hit.PSObject.Properties.Name -contains "keywords") {
        foreach ($keyword in ConvertTo-A12Array $Hit.keywords) {
            if ($null -ne $keyword) {
                $parts.Add([string]$keyword)
            }
        }
    }
    return ConvertTo-A12NormalizedText ($parts -join " ")
}

function Test-A12TermMatch {
    param(
        [Parameter(Mandatory = $true)][string]$SearchText,
        [AllowNull()][object]$Term
    )

    $normalizedTerm = ConvertTo-A12NormalizedText $Term
    if ([string]::IsNullOrWhiteSpace($normalizedTerm)) {
        return $false
    }
    return $SearchText.Contains($normalizedTerm)
}

function Measure-A12Hit {
    param(
        [Parameter(Mandatory = $true)][object]$Case,
        [Parameter(Mandatory = $true)][object]$Hit
    )

    $searchText = Get-A12HitSearchText -Hit $Hit
    $anchorMatches = @()
    foreach ($anchor in ConvertTo-A12Array $Case.expectedAnchors) {
        if (Test-A12TermMatch -SearchText $searchText -Term $anchor) {
            $anchorMatches += [string]$anchor
        }
    }
    if ($anchorMatches.Count -gt 0) {
        return [pscustomobject]@{
            Classification = "STRONG_HIT"
            MatchedAnchors = $anchorMatches
            MatchedConcepts = @()
        }
    }

    $concepts = @(ConvertTo-A12Array $Case.expectedConcepts | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    $conceptMatches = @()
    foreach ($concept in $concepts) {
        if (Test-A12TermMatch -SearchText $searchText -Term $concept) {
            $conceptMatches += [string]$concept
        }
    }
    $requiredConcepts = 0
    if ($concepts.Count -eq 1) {
        $requiredConcepts = 1
    }
    elseif ($concepts.Count -gt 1) {
        $requiredConcepts = 2
    }
    if ($requiredConcepts -gt 0 -and $conceptMatches.Count -ge $requiredConcepts) {
        return [pscustomobject]@{
            Classification = "WEAK_HIT"
            MatchedAnchors = @()
            MatchedConcepts = $conceptMatches
        }
    }

    return [pscustomobject]@{
        Classification = "MISS"
        MatchedAnchors = @()
        MatchedConcepts = $conceptMatches
    }
}

function New-A12ChunkRecord {
    param(
        [Parameter(Mandatory = $true)][object]$Case,
        [Parameter(Mandatory = $true)][object]$Hit,
        [Parameter(Mandatory = $true)][int]$Rank
    )

    $measurement = Measure-A12Hit -Case $Case -Hit $Hit
    $content = ""
    if ($Hit.PSObject.Properties.Name -contains "content" -and $null -ne $Hit.content) {
        $content = [string]$Hit.content
    }
    elseif ($Hit.PSObject.Properties.Name -contains "preview" -and $null -ne $Hit.preview) {
        $content = [string]$Hit.preview
    }

    $score = $null
    if ($Hit.PSObject.Properties.Name -contains "scorePercent") {
        $score = $Hit.scorePercent
    }
    elseif ($Hit.PSObject.Properties.Name -contains "score") {
        $score = $Hit.score
    }

    $chunkNo = $null
    if ($Hit.PSObject.Properties.Name -contains "chunkNo") {
        $chunkNo = $Hit.chunkNo
    }

    return [pscustomobject]@{
        rank = $Rank
        chunkId = $Hit.chunkId
        materialId = $Hit.materialId
        chunkNo = $chunkNo
        sourceFilename = $Hit.sourceFilename
        title = $Hit.title
        score = $score
        hitReason = $Hit.hitReason
        classification = $measurement.Classification
        matchedAnchors = @($measurement.MatchedAnchors)
        matchedConcepts = @($measurement.MatchedConcepts)
        preview = New-A12Preview -Value $content -MaxLength 120
    }
}

function Measure-A12QueryResult {
    param(
        [Parameter(Mandatory = $true)][object]$Case,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Hits,
        [Parameter(Mandatory = $true)][int]$TopK,
        [AllowNull()][object]$RequestedMaterialId = $null
    )

    $records = @()
    for ($i = 0; $i -lt $Hits.Count; $i++) {
        $records += New-A12ChunkRecord -Case $Case -Hit $Hits[$i] -Rank ($i + 1)
    }

    $firstStrong = $records | Where-Object { $_.classification -eq "STRONG_HIT" } | Select-Object -First 1
    $firstAny = $records | Where-Object { $_.classification -ne "MISS" } | Select-Object -First 1

    $rr = 0.0
    if ($null -ne $firstAny) {
        $rr = 1.0 / [double]$firstAny.rank
    }

    $materialPrecision = $null
    if ($null -ne $RequestedMaterialId) {
        if ($records.Count -eq 0) {
            $materialPrecision = 0.0
        }
        else {
            $sameMaterial = @($records | Where-Object { [string]$_.materialId -eq [string]$RequestedMaterialId }).Count
            $materialPrecision = [double]$sameMaterial / [double]$records.Count
        }
    }

    return [pscustomobject]@{
        id = $Case.id
        question = $Case.question
        topK = $TopK
        returnedChunkCount = $records.Count
        classification = if ($null -ne $firstStrong) { "STRONG_HIT" } elseif ($null -ne $firstAny) { "WEAK_HIT" } else { "MISS" }
        hit = $null -ne $firstAny
        strongHit = $null -ne $firstStrong
        reciprocalRank = $rr
        materialPrecision = $materialPrecision
        expectedAnchors = @(ConvertTo-A12Array $Case.expectedAnchors)
        expectedConcepts = @(ConvertTo-A12Array $Case.expectedConcepts)
        expectedChapter = if ($Case.PSObject.Properties.Name -contains "expectedChapter") { $Case.expectedChapter } else { $null }
        notes = if ($Case.PSObject.Properties.Name -contains "notes") { $Case.notes } else { $null }
        chunks = @($records)
    }
}

function Measure-A12Aggregate {
    param([Parameter(Mandatory = $true)][object[]]$QueryResults)

    if ($QueryResults.Count -eq 0) {
        throw "Cannot aggregate an empty result set."
    }
    $strongCount = @($QueryResults | Where-Object { $_.strongHit }).Count
    $anyCount = @($QueryResults | Where-Object { $_.hit }).Count
    $rrSum = 0.0
    foreach ($result in $QueryResults) {
        $rrSum += [double]$result.reciprocalRank
    }
    $materialValues = @($QueryResults | Where-Object { $null -ne $_.materialPrecision } | ForEach-Object { [double]$_.materialPrecision })
    $materialPrecision = $null
    if ($materialValues.Count -gt 0) {
        $sum = 0.0
        foreach ($value in $materialValues) {
            $sum += $value
        }
        $materialPrecision = $sum / [double]$materialValues.Count
    }

    return [pscustomobject]@{
        questionCount = $QueryResults.Count
        strongHitAtK = [double]$strongCount / [double]$QueryResults.Count
        anyHitAtK = [double]$anyCount / [double]$QueryResults.Count
        mrr = $rrSum / [double]$QueryResults.Count
        materialPrecision = $materialPrecision
    }
}

function Format-A12Metric {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return "n/a"
    }
    return ([double]$Value).ToString("0.000")
}
