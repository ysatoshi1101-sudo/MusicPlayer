# ============================================================
# dev-helpers.ps1
#
# Claudeが提示したコードを、Android Studioのターミナル(PowerShell)に
# 貼り付けて、プロジェクトのファイルへ反映するための補助関数です。
#
# 【使い方】ターミナルがプロジェクトのルート(.gitがあるフォルダ)を
# 開いている状態で、ターミナルを開くたびに1回だけ実行します。
#
#     . .\tools\dev-helpers.ps1
#
# 【用意している関数】
#   Write-SrcFile   ファイルを丸ごと作成/置き換える
#   Replace-InFile  ファイルの一部分を置き換える
#                   (貼り付けた「置き換え前」の文章が、ファイル内にちょうど1か所
#                    だけ見つかったときだけ実行する。0か所/2か所以上は何もしない)
#
# 【共通の動き】
#   ・文字コードは BOMなしUTF-8 で保存する
#   ・改行コードは、既存ファイルの改行(CRLF/LF)に合わせる
#   ・結果は「OK」(緑)か「NG」(赤)で表示する。NGのときはそのファイルを変更しない
# ============================================================

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Get-FullPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $Path))
}

function ConvertTo-Lf {
    param([string]$Text)
    return ($Text -replace "`r`n", "`n") -replace "`r", "`n"
}

# ファイルを丸ごと作成/置き換える
function Write-SrcFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $full = Get-FullPath $Path
    $existed = Test-Path -LiteralPath $full
    $eol = [Environment]::NewLine
    if ($existed) {
        $current = [System.IO.File]::ReadAllText($full)
        if ($current.Contains("`r`n")) { $eol = "`r`n" } else { $eol = "`n" }
    }

    $dir = [System.IO.Path]::GetDirectoryName($full)
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }

    $text = (ConvertTo-Lf $Content).TrimEnd("`n") + "`n"
    $text = $text.Replace("`n", $eol)
    [System.IO.File]::WriteAllText($full, $text, $script:Utf8NoBom)

    if ($existed) {
        Write-Host "OK  置き換え: $Path" -ForegroundColor Green
    } else {
        Write-Host "OK  新規作成: $Path" -ForegroundColor Green
    }
}

# ファイルの一部分を置き換える(ちょうど1か所だけ一致したときのみ)
function Replace-InFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New
    )

    $full = Get-FullPath $Path
    if (-not (Test-Path -LiteralPath $full)) {
        Write-Host "NG  ファイルが見つかりません: $Path" -ForegroundColor Red
        return
    }

    $raw = [System.IO.File]::ReadAllText($full)
    if ($raw.Contains("`r`n")) { $eol = "`r`n" } else { $eol = "`n" }

    # 比較は改行をLFにそろえて行い、書き戻すときに元の改行へ戻す
    $text = ConvertTo-Lf $raw
    $oldN = (ConvertTo-Lf $Old).Trim("`n")
    $newN = (ConvertTo-Lf $New).Trim("`n")

    if ($oldN.Length -eq 0) {
        Write-Host "NG  置き換え前の文章が空です: $Path" -ForegroundColor Red
        return
    }

    $count = 0
    $first = -1
    $index = 0
    while (($index = $text.IndexOf($oldN, $index, [System.StringComparison]::Ordinal)) -ge 0) {
        if ($count -eq 0) { $first = $index }
        $count++
        $index += $oldN.Length
    }

    if ($count -eq 0) {
        Write-Host "NG  置き換え前の文章が見つかりません(変更していません): $Path" -ForegroundColor Red
        return
    }
    if ($count -gt 1) {
        Write-Host "NG  置き換え前の文章が${count}か所見つかりました。1か所に特定できないため変更していません: $Path" -ForegroundColor Red
        return
    }

    $result = $text.Substring(0, $first) + $newN + $text.Substring($first + $oldN.Length)
    $result = $result.Replace("`n", $eol)
    [System.IO.File]::WriteAllText($full, $result, $script:Utf8NoBom)
    Write-Host "OK  一部置き換え: $Path" -ForegroundColor Green
}
