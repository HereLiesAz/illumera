' Stream text parsing, ported from Android's StreamParser/StreamQuality and web/src/core/parser.ts.
' Keep all three in step; docs/ADDONS.md documents these patterns for addon authors.

' Higher is better.
function QualityOrder(quality as String) as Integer
    return { "4k": 5, "1080p": 4, "720p": 3, "sd": 2, "cam": 1, "unknown": 0 }[quality]
end function

function QualityOf(text as String) as String
    ' Explicit resolutions win over words.
    rules = [
        ["\b2160[pi]?\b", "4k"],
        ["\b1080[pi]?\b", "1080p"],
        ["\b720[pi]?\b", "720p"],
        ["\b480[pi]?\b", "sd"],
        ["\b(4k|uhd|ultra\s*hd)\b", "4k"],
        ["\bfhd\b", "1080p"],
        ["\b(cam|camrip|ts|telesync|hdts|hdcam|telecine|tc)\b", "cam"],
        ["\b(sd|dvd|dvdrip)\b", "sd"],
        ["\bHD\b", "720p"]
    ]
    for each rule in rules
        if CreateObject("roRegex", rule[0], "i").IsMatch(text) then return rule[1]
    end for
    return "unknown"
end function

function TextOr(value as Dynamic) as String
    if value = invalid then return ""
    return value.ToStr()
end function

' name, title, description and filename, space-joined.
function CombinedText(stream as Object) as String
    parts = []
    for each field in [stream.name, stream.title, stream.description, StreamFilename(stream)]
        if field <> invalid and field <> "" then parts.Push(field)
    end for
    return parts.Join(" ")
end function

function StreamFilename(stream as Object) as Dynamic
    if stream.behaviorHints = invalid then return invalid
    return stream.behaviorHints.filename
end function

' Bytes from "1.5 GB"-style text, in 1024s; invalid when there is none.
function SizeBytes(text as String) as Dynamic
    ' No (?:) groups: the brs test interpreter doesn't support them. ("m" is reserved.)
    found = CreateObject("roRegex", "(\d+(\.\d+)?)\s?(KB|MB|GB|TB)", "i").Match(text)
    if found.Count() < 4 then return invalid
    units = { "KB": 1024#, "MB": 1048576#, "GB": 1073741824#, "TB": 1099511627776# }
    ' Double: sizes pass 2 GB, beyond Integer.
    bytes = Val(found[1]) * units[UCase(found[3])]
    if bytes <= 0 then return invalid
    return bytes
end function

' Seeders; the "S" form needs a separator so "S02E05" isn't read as seeds.
function Seeds(text as String) as Dynamic
    for each pattern in ["👤\s*(\d[\d,.]*)", "\bseeds?[:\s]+(\d[\d,.]*)", "\bS[:\s]+(\d[\d,.]*)"]
        found = CreateObject("roRegex", pattern, "i").Match(text)
        if found.Count() > 1 then
            digits = CreateObject("roRegex", "[,.]", "").ReplaceAll(found[1], "")
            return Val(digits, 10)
        end if
    end for
    return invalid
end function

function Formats(text as String) as Object
    patterns = {
        dv: "\b(dolby\s*vision|dovi|dv)\b",
        hdr: "\b(hdr10\+?|hdr|hlg)\b",
        dts: "\b(dts[-\s]?(hd|x|ma)?)\b",
        dolby: "\b(dolby\s*(digital|atmos)?|dd[+\s]?[257]\.?1?|atmos|ac-?3|eac-?3)\b",
        hevc: "\b(hevc|h\.?265|x\.?265)\b",
        av1: "\bav1\b",
        threeD: "\b(3d|sbs|half.?sbs|hou)\b"
    }
    found = {}
    for each name in patterns
        if CreateObject("roRegex", patterns[name], "i").IsMatch(text) then
            key = name
            if name = "threed" then key = "3d"
            found[key] = true
        end if
    end for
    return found
end function

function ParseStream(stream as Object) as Object
    text = CombinedText(stream)
    ' Quality from the first field that has one: filename, title, description, name.
    quality = "unknown"
    for each field in [StreamFilename(stream), stream.title, stream.description, stream.name]
        if field <> invalid and field <> "" then
            quality = QualityOf(field)
            if quality <> "unknown" then exit for
        end if
    end for
    size = invalid
    if stream.behaviorHints <> invalid and stream.behaviorHints.videoSize <> invalid then size = stream.behaviorHints.videoSize
    if size = invalid then size = SizeBytes(text)
    return { quality: quality, sizeBytes: size, seeds: Seeds(text), formats: Formats(text) }
end function
