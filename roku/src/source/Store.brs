' Settings and state kept in the Roku registry (section "illumera"), as JSON.

function StoreGet(key as String, defaultValue as Dynamic) as Dynamic
    section = CreateObject("roRegistrySection", "illumera")
    if not section.Exists(key) then return defaultValue
    value = ParseJson(section.Read(key))
    if value = invalid then return defaultValue
    return value
end function

' False when the registry refused the write (over its 16 KB, or a failed flush).
function StoreSet(key as String, value as Dynamic) as Boolean
    section = CreateObject("roRegistrySection", "illumera")
    if not section.Write(key, FormatJson(value)) then return false
    return section.Flush()
end function

' Installed addons: [{ transportUrl, manifest, enabled }]. Cinemeta and OpenSubtitles on first run.
function Addons() as Object
    return StoreGet("addons", [])
end function

' The registry holds 16 KB per channel, so only what the app reads is kept of a manifest.
function TrimManifest(manifest as Object) as Object
    catalogs = []
    if manifest.catalogs <> invalid then
        for each c in manifest.catalogs
            extras = []
            if c.extra <> invalid then
                for each e in c.extra
                    extras.Push({ name: e.name, isRequired: e.isRequired = true })
                end for
            end if
            catalogs.Push({ id: c.id, type: c.type, name: c.name, extra: extras })
        end for
    end if
    return {
        id: manifest.id, name: manifest.name, version: manifest.version,
        resources: manifest.resources, types: manifest.types, idPrefixes: manifest.idPrefixes,
        catalogs: catalogs
    }
end function

function SaveAddons(list as Object) as Boolean
    return StoreSet("addons", list)
end function

function EnabledAddons() as Object
    out = []
    for each a in Addons()
        if a.enabled <> false then out.Push(a)
    end for
    return out
end function

function AddonOrder() as Object
    out = []
    for each a in EnabledAddons()
        out.Push(a.transportUrl)
    end for
    return out
end function

function Supports(addon as Object, resource as String) as Boolean
    if addon.manifest = invalid or addon.manifest.resources = invalid then return false
    for each r in addon.manifest.resources
        name = r
        if Type(r) = "roAssociativeArray" then name = r.name
        if name = resource or (resource = "subtitles" and name = "subtitle") then return true
    end for
    return false
end function

' Top-level idPrefixes; none means every id.
function AcceptsId(addon as Object, id as String) as Boolean
    prefixes = addon.manifest.idPrefixes
    if prefixes = invalid or prefixes.Count() = 0 then return true
    lower = LCase(id)
    for each p in prefixes
        if p <> "" and Left(lower, Len(p)) = LCase(p) then return true
    end for
    return false
end function

' Whether the addon lists this type, or lists none (then it accepts every type).
function AcceptsType(addon as Object, mediaType as String) as Boolean
    types = addon.manifest.types
    if types = invalid or types.Count() = 0 then return true
    for each t in types
        if t = mediaType then return true
    end for
    return false
end function

' Base URL from what the user types: stremio://, a manifest URL, or a base. Public http
' hosts are upgraded to https, as on the web (docs/ADDONS.md).
function TransportUrl(text as String) as String
    url = text.Trim()
    if LCase(Left(url, 10)) = "stremio://" then url = "https://" + Mid(url, 11)
    url = CreateObject("roRegex", "/manifest\.json(\?.*)?$", "i").ReplaceAll(url, "")
    while Right(url, 1) = "/"
        url = Left(url, Len(url) - 1)
    end while
    return UpgradeUrl(url)
end function

' http:// to https:// unless the host is local (localhost, .local/.lan/.home.arpa names,
' private or loopback IPv4 literals, local IPv6 literals). A default :80 port is dropped.
function UpgradeUrl(url as String) as String
    found = CreateObject("roRegex", "^http://(\[[^\]/?#]*\]|[^/:?#]+)(:\d+)?", "i").Match(url)
    if found.Count() < 2 then return url
    host = LCase(found[1])
    if IsLocalHost(host) then return url
    port = ""
    if found.Count() > 2 then port = found[2]
    if port = ":80" then port = ""
    return "https://" + found[1] + port + Mid(url, Len(found[0]) + 1)
end function

function IsLocalHost(host as String) as Boolean
    if host = "localhost" then return true
    if CreateObject("roRegex", "\.(local|lan|home\.arpa)$", "").IsMatch(host) then return true
    quad = CreateObject("roRegex", "^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$", "").Match(host)
    if quad.Count() = 5 then
        a = Val(quad[1])
        b = Val(quad[2])
        if Val(quad[3]) > 255 or Val(quad[4]) > 255 or a > 255 or b > 255 then return false
        if a = 127 or a = 10 then return true
        if a = 192 and b = 168 then return true
        if a = 169 and b = 254 then return true
        return a = 172 and b >= 16 and b <= 31
    end if
    if Left(host, 1) = "[" then
        inner = Mid(host, 2, Len(host) - 2)
        if inner = "::1" then return true
        return CreateObject("roRegex", "^(f[cd][0-9a-f]{2}|fe[89ab][0-9a-f]):", "").IsMatch(inner)
    end if
    return false
end function

' Path-safe id: encoded, but ":" kept as addons expect ("tt1:2:3").
function EncodeId(id as String) as String
    return CreateObject("roRegex", "%3A", "i").ReplaceAll(id.EncodeUriComponent(), ":")
end function

' Past this fraction a video counts as watched and leaves Continue watching (as Android's default and web/).
function WatchedFraction() as Float
    return 0.85
end function

' Watch progress per title: { id: { type, id, name, poster, videoId, time, duration, updatedAt } }.
function Progress() as Object
    return StoreGet("progress", {})
end function

' Keeps the newest 40 titles, to stay inside the registry's 16 KB.
sub SaveProgress(entry as Object)
    all = Progress()
    all[entry.id] = entry
    list = []
    for each id in all
        list.Push(all[id])
    end for
    list.SortBy("updatedAt", "r")
    kept = {}
    for i = 0 to list.Count() - 1
        if i >= 40 then exit for
        kept[list[i].id] = list[i]
    end for
    StoreSet("progress", kept)
end sub

' TorrServer or Stremio streaming server on the network, for torrents; "" when none is set.
function StreamingServer() as String
    return StoreGet("streamingServer", "")
end function
