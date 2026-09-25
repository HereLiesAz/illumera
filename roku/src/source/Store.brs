' Settings and state kept in the Roku registry (section "illumera"), as JSON.

function StoreGet(key as String, defaultValue as Dynamic) as Dynamic
    section = CreateObject("roRegistrySection", "illumera")
    if not section.Exists(key) then return defaultValue
    value = ParseJson(section.Read(key))
    if value = invalid then return defaultValue
    return value
end function

sub StoreSet(key as String, value as Dynamic)
    section = CreateObject("roRegistrySection", "illumera")
    section.Write(key, FormatJson(value))
    section.Flush()
end sub

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

sub SaveAddons(list as Object)
    StoreSet("addons", list)
end sub

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

' Base URL from what the user types: stremio://, a manifest URL, or a base.
function TransportUrl(text as String) as String
    url = text.Trim()
    if LCase(Left(url, 10)) = "stremio://" then url = "https://" + Mid(url, 11)
    url = CreateObject("roRegex", "/manifest\.json(\?.*)?$", "i").ReplaceAll(url, "")
    while Right(url, 1) = "/"
        url = Left(url, Len(url) - 1)
    end while
    return url
end function

' Path-safe id: encoded, but ":" kept as addons expect ("tt1:2:3").
function EncodeId(id as String) as String
    return CreateObject("roRegex", "%3A", "i").ReplaceAll(id.EncodeUriComponent(), ":")
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
