# One-off audit: compare every hydrofarm crafting recipe against all vanilla 26.1.2 recipes,
# matching the way the game does: trimmed patterns, symbol-agnostic, horizontal mirror,
# tag-vs-item overlap (vanilla item tags resolved recursively), shapeless multisets, and
# crafting_transmute treated as 2-ingredient shapeless.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = "$env:USERPROFILE\.gradle\caches\fabric-loom\26.1.2\minecraft-merged.jar"
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)

function Read-Entry($entry) {
    $r = New-Object System.IO.StreamReader($entry.Open())
    try { return $r.ReadToEnd() } finally { $r.Close() }
}

# ---- vanilla item tags, recursively resolved to concrete item sets ----
$rawTags = @{}
foreach ($e in $zip.Entries | Where-Object { $_.FullName -like 'data/minecraft/tags/item/*.json' }) {
    $j = (Read-Entry $e) | ConvertFrom-Json -AsHashtable
    $name = 'minecraft:' + [System.IO.Path]::GetFileNameWithoutExtension($e.Name)
    $vals = @()
    foreach ($v in @($j['values'])) {
        if ($v -is [string]) { $vals += $v } elseif ($v -is [System.Collections.IDictionary] -and $v.Contains('id')) { $vals += [string]$v['id'] }
    }
    $rawTags[$name] = $vals
}
$resolvedTags = @{}
function Resolve-Tag([string]$tag) {
    if ($resolvedTags.ContainsKey($tag)) { return $resolvedTags[$tag] }
    $resolvedTags[$tag] = @()   # cycle guard
    $out = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($v in @($rawTags[$tag])) {
        if ($null -eq $v) { continue }
        if ($v.StartsWith('#')) { foreach ($x in (Resolve-Tag $v.Substring(1))) { [void]$out.Add($x) } }
        else { [void]$out.Add($v) }
    }
    $resolvedTags[$tag] = @($out)
    return $resolvedTags[$tag]
}
foreach ($t in @($rawTags.Keys)) { [void](Resolve-Tag $t) }

# ---- ingredient normalization: -> sorted list of alternatives ("ns:item" or "#ns:tag") ----
function Norm-Ing($ing) {
    if ($null -eq $ing) { return @() }
    if ($ing -is [string]) { return @($ing) }
    if ($ing -is [System.Collections.IDictionary]) {              # pre-1.21.2 object form
        if ($ing.Contains('item')) { return @([string]$ing['item']) }
        if ($ing.Contains('tag'))  { return @('#' + [string]$ing['tag']) }
        return @()
    }
    if ($ing -is [System.Collections.IEnumerable]) {
        $all = @(); foreach ($x in $ing) { $all += Norm-Ing $x }; return @($all | Sort-Object -Unique)
    }
    return @()
}

# do two single alternatives overlap (could one item satisfy both)?
function Alt-Overlap([string]$a, [string]$b) {
    if ($a -eq $b) { return $true }
    $aTag = $a.StartsWith('#'); $bTag = $b.StartsWith('#')
    if ($aTag -and -not $bTag) { return (Resolve-Tag $a.Substring(1)) -contains $b }
    if ($bTag -and -not $aTag) { return (Resolve-Tag $b.Substring(1)) -contains $a }
    if ($aTag -and $bTag) {
        $sa = Resolve-Tag $a.Substring(1)
        foreach ($x in (Resolve-Tag $b.Substring(1))) { if ($sa -contains $x) { return $true } }
    }
    return $false
}
# cell = '|'-joined alternatives, '' = empty
function Cell-Overlap([string]$a, [string]$b) {
    if ($a -eq '' -or $b -eq '') { return ($a -eq $b) }
    foreach ($x in $a.Split('|')) { foreach ($y in $b.Split('|')) { if (Alt-Overlap $x $y) { return $true } } }
    return $false
}

# ---- parse a crafting recipe json into a normalized record ----
function Parse-Recipe([string]$json, [string]$id) {
    $j = $json | ConvertFrom-Json -AsHashtable
    switch ([string]$j['type']) {
        'minecraft:crafting_shaped' {
            $key = @{}
            if ($null -ne $j['key']) {
                foreach ($p in $j['key'].GetEnumerator()) { $key[[string]$p.Key] = ((Norm-Ing $p.Value) -join '|') }
            }
            $grid = @()
            foreach ($row in @($j['pattern'])) {
                $cells = @()
                foreach ($ch in $row.ToCharArray()) {
                    $s = [string]$ch
                    $cells += $(if ($s -eq ' ') { '' } else { [string]$key[$s] })
                }
                $grid += ,$cells
            }
            # trim empty rows/cols (the game matches translated patterns)
            $rMin=-1;$rMax=-1;$cMin=99;$cMax=-1
            for ($r=0; $r -lt $grid.Count; $r++) {
                $rowHas = $false
                for ($c=0; $c -lt $grid[$r].Count; $c++) {
                    if ($grid[$r][$c] -ne '') { $rowHas=$true; if ($c -lt $cMin){$cMin=$c}; if ($c -gt $cMax){$cMax=$c} }
                }
                if ($rowHas) { if ($rMin -lt 0){$rMin=$r}; $rMax=$r }
            }
            if ($rMin -lt 0) { return $null }
            $trim = @()
            for ($r=$rMin; $r -le $rMax; $r++) { $trim += ,@($grid[$r][$cMin..$cMax]) }
            $mirror = @(); foreach ($row in $trim) { $rev = @($row); [array]::Reverse($rev); $mirror += ,$rev }
            $cells = @(); foreach ($row in $trim) { foreach ($c in $row) { if ($c -ne '') { $cells += $c } } }
            return [pscustomobject]@{ id=$id; kind='shaped'; grid=$trim; mirror=$mirror
                rows=$trim.Count; cols=$trim[0].Count
                sig = (($trim | ForEach-Object { $_ -join ',' }) -join '/')
                msig = (($mirror | ForEach-Object { $_ -join ',' }) -join '/')
                multiset = (@($cells | Sort-Object) -join '+') }
        }
        'minecraft:crafting_shapeless' {
            $ings = @(); foreach ($i in @($j['ingredients'])) { $ings += ((Norm-Ing $i) -join '|') }
            return [pscustomobject]@{ id=$id; kind='shapeless'; ings=@($ings | Sort-Object)
                multiset = (@($ings | Sort-Object) -join '+') }
        }
        'minecraft:crafting_transmute' {
            $ings = @(((Norm-Ing $j['input']) -join '|'), ((Norm-Ing $j['material']) -join '|'))
            return [pscustomobject]@{ id=$id; kind='shapeless'; ings=@($ings | Sort-Object)
                multiset = (@($ings | Sort-Object) -join '+') }
        }
        default { return $null }
    }
}

# ---- load both recipe sets ----
$vanilla = @()
$objectFormCount = 0
foreach ($e in $zip.Entries | Where-Object { $_.FullName -like 'data/minecraft/recipe/*.json' }) {
    $raw = Read-Entry $e
    if ($raw -match '"(item|tag)"\s*:\s*"') { $objectFormCount++ }
    $rec = Parse-Recipe $raw ('minecraft:' + [System.IO.Path]::GetFileNameWithoutExtension($e.Name))
    if ($null -ne $rec) { $vanilla += $rec }
}
$zip.Dispose()

$ours = @()
foreach ($f in Get-ChildItem 'shared-resources\data\hydrofarm\recipe\*.json') {
    $rec = Parse-Recipe (Get-Content $f.FullName -Raw) ('hydrofarm:' + $f.BaseName)
    if ($null -ne $rec) { $ours += $rec }
}

"Vanilla crafting recipes parsed: $($vanilla.Count)  (raw files containing object-form ingredients: $objectFormCount)"
"Hydrofarm crafting recipes parsed: $($ours.Count)"
''

# ---- pass 1: exact signature equality (the copper-bars class) ----
"== EXACT conflicts (same trimmed pattern or multiset, incl. mirrored) =="
$found = 0
foreach ($o in $ours) {
    foreach ($v in $vanilla) {
        if ($o.kind -ne $v.kind) { continue }
        if ($o.kind -eq 'shaped') {
            if ($o.sig -eq $v.sig -or $o.sig -eq $v.msig) { "  $($o.id)  ==  $($v.id)   [$($o.sig)]"; $found++ }
        } else {
            if ($o.multiset -eq $v.multiset) { "  $($o.id)  ==  $($v.id)   [$($o.multiset)]"; $found++ }
        }
    }
}
if ($found -eq 0) { '  (none)' }
''

# ---- pass 2: overlap conflicts (tag/item intersection; one grid arrangement satisfies both) ----
"== OVERLAP conflicts (an arrangement exists that matches both) =="
$found = 0
foreach ($o in $ours | Where-Object kind -eq 'shaped') {
    foreach ($v in $vanilla | Where-Object kind -eq 'shaped') {
        if ($o.rows -ne $v.rows -or $o.cols -ne $v.cols) { continue }
        foreach ($vGrid in @(,$v.grid; ,$v.mirror)) {
            $all = $true
            for ($r=0; $r -lt $o.rows -and $all; $r++) {
                for ($c=0; $c -lt $o.cols -and $all; $c++) {
                    if (-not (Cell-Overlap $o.grid[$r][$c] $vGrid[$r][$c])) { $all = $false }
                }
            }
            if ($all) { "  $($o.id)  ~~  $($v.id)"; $found++; break }
        }
    }
}
# shapeless-vs-shapeless overlap (incl. transmute), pairwise greedy on sorted lists
foreach ($o in $ours | Where-Object kind -eq 'shapeless') {
    foreach ($v in $vanilla | Where-Object kind -eq 'shapeless') {
        if ($o.ings.Count -ne $v.ings.Count) { continue }
        $rem = [System.Collections.ArrayList]@($v.ings)
        $ok = $true
        foreach ($i in $o.ings) {
            $hit = -1
            for ($k=0; $k -lt $rem.Count; $k++) { if (Cell-Overlap $i $rem[$k]) { $hit=$k; break } }
            if ($hit -lt 0) { $ok=$false; break } else { $rem.RemoveAt($hit) }
        }
        if ($ok) { "  $($o.id)  ~~  $($v.id)"; $found++ }
    }
}
# our shaped's filled cells as a multiset vs vanilla shapeless (laying out our shape satisfies both)
foreach ($o in $ours | Where-Object kind -eq 'shaped') {
    $oCells = @($o.multiset -split '\+')
    foreach ($v in $vanilla | Where-Object kind -eq 'shapeless') {
        if ($oCells.Count -ne $v.ings.Count) { continue }
        $rem = [System.Collections.ArrayList]@($v.ings)
        $ok = $true
        foreach ($i in $oCells) {
            $hit = -1
            for ($k=0; $k -lt $rem.Count; $k++) { if (Cell-Overlap $i $rem[$k]) { $hit=$k; break } }
            if ($hit -lt 0) { $ok=$false; break } else { $rem.RemoveAt($hit) }
        }
        if ($ok) { "  $($o.id)  ~~  $($v.id)  (our shaped layout also satisfies this vanilla shapeless)"; $found++ }
    }
}
if ($found -eq 0) { '  (none)' }
