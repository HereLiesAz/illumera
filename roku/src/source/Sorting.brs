' Stream filtering and ranking, ported from Android's StreamSortingService and
' web/src/core/sorting.ts, with the same defaults. Language requirements are not ported yet.

function DefaultSortPrefs() as Object
    return {
        enabledQualities: ["4k", "1080p", "720p", "unknown"],
        excludePhrases: [],
        maxSizeGb: 0,
        primarySort: "quality",
        secondarySort: "size",
        movieTargetSizeMb: 3000,
        episodeTargetSizeMb: 750,
        minimumSeeds: 5
    }
end function

function ArrayHas(list as Object, value as Dynamic) as Boolean
    for each item in list
        if item = value then return true
    end for
    return false
end function

function Cmp(a as Double, b as Double) as Integer
    if a < b then return -1
    if a > b then return 1
    return 0
end function

function SortKeyCompare(key as String, a as Object, b as Object) as Integer
    if key = "size" then
        return Cmp(NumOr(b.info.sizeBytes, 0), NumOr(a.info.sizeBytes, 0))
    else if key = "seeds" then
        tierA = SeedTier(a.info.seeds)
        tierB = SeedTier(b.info.seeds)
        if tierA <> tierB then return tierB - tierA
        return Cmp(NumOr(b.info.seeds, -1), NumOr(a.info.seeds, -1))
    end if
    return QualityOrder(b.info.quality) - QualityOrder(a.info.quality)
end function

function SeedTier(count as Dynamic) as Integer
    if count = invalid then return 1
    if count > 0 then return 2
    return 0
end function

function NumOr(value as Dynamic, fallback as Double) as Double
    if value = invalid then return fallback
    return value
end function

' Filters and ranks streams from every addon: closeness to the target size, then seeders
' below the minimum, then addon order, then the chosen sort keys. Stable.
function SortStreams(streams as Object, mediaType as String, ranking as Object, prefs = invalid as Dynamic) as Object
    if prefs = invalid then prefs = DefaultSortPrefs()
    targetMb = prefs.episodeTargetSizeMb
    if mediaType = "movie" then targetMb = prefs.movieTargetSizeMb
    target = targetMb * 1048576#
    maxBytes = prefs.maxSizeGb * 1073741824#

    ranked = []
    for i = 0 to streams.Count() - 1
        stream = streams[i]
        info = ParseStream(stream)
        keep = ArrayHas(prefs.enabledQualities, info.quality)
        if keep and prefs.excludePhrases.Count() > 0 then
            text = LCase(CombinedText(stream))
            for each phrase in prefs.excludePhrases
                if phrase <> "" and Instr(1, text, LCase(phrase)) > 0 then keep = false
            end for
        end if
        if keep and maxBytes > 0 and info.sizeBytes <> invalid and info.sizeBytes > maxBytes then keep = false
        if keep then
            gap = 0#
            if target > 0 then
                if info.sizeBytes = invalid then gap = 1e18 else gap = Abs(info.sizeBytes - target)
            end if
            seedGap = 0
            if prefs.minimumSeeds > 0 and info.seeds <> invalid and info.seeds < prefs.minimumSeeds then seedGap = prefs.minimumSeeds - info.seeds
            order = 1e9
            for j = 0 to ranking.Count() - 1
                if ranking[j] = stream.addonBase then order = j
            end for
            ranked.Push({ stream: stream, info: info, gap: gap, seedGap: seedGap, order: order, index: i })
        end if
    end for

    keys = [prefs.primarySort]
    secondary = prefs.secondarySort
    if secondary = prefs.primarySort then
        if prefs.primarySort = "quality" then secondary = "size" else secondary = "quality"
    end if
    keys.Push(secondary)
    for each k in ["quality", "size", "seeds"]
        if not ArrayHas(keys, k) then keys.Push(k)
    end for

    sorted = MergeSort(ranked, keys)
    out = []
    for each r in sorted
        out.Push(r.stream)
    end for
    return out
end function

function RankCompare(a as Object, b as Object, keys as Object) as Integer
    c = Cmp(a.gap, b.gap)
    if c <> 0 then return c
    c = a.seedGap - b.seedGap
    if c <> 0 then return c
    c = Cmp(a.order, b.order)
    if c <> 0 then return c
    for each key in keys
        c = SortKeyCompare(key, a, b)
        if c <> 0 then return c
    end for
    return a.index - b.index
end function

function MergeSort(items as Object, keys as Object) as Object
    if items.Count() <= 1 then return items
    middle = Int(items.Count() / 2)
    left = []
    right = []
    for i = 0 to items.Count() - 1
        if i < middle then left.Push(items[i]) else right.Push(items[i])
    end for
    left = MergeSort(left, keys)
    right = MergeSort(right, keys)
    out = []
    l = 0
    r = 0
    while l < left.Count() and r < right.Count()
        if RankCompare(left[l], right[r], keys) <= 0 then
            out.Push(left[l])
            l = l + 1
        else
            out.Push(right[r])
            r = r + 1
        end if
    end while
    while l < left.Count()
        out.Push(left[l])
        l = l + 1
    end while
    while r < right.Count()
        out.Push(right[r])
        r = r + 1
    end while
    return out
end function
