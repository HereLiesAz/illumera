sub init()
    m.top.functionName = "RunRequest"
end sub

sub RunRequest()
    req = m.top.request
    result = { tag: req.tag, ok: false }
    if req.kind = "json" then
        data = GetJson(req.url, 20000)
        result.ok = data <> invalid
        result.data = data
    else if req.kind = "install" then
        result = Install(req)
    else if req.kind = "torrent" then
        result = TorrServerUrl(req)
    end if
    m.top.response = result
end sub

function Transfer(url as String) as Object
    t = CreateObject("roUrlTransfer")
    t.SetUrl(url)
    t.SetCertificatesFile("common:/certs/ca-bundle.crt")
    t.InitClientCertificates()
    t.EnableEncodings(true)
    t.AddHeader("Accept", "application/json")
    return t
end function

' GET JSON with a timeout; invalid on failure.
function GetJson(url as String, timeoutMs as Integer) as Dynamic
    t = Transfer(url)
    port = CreateObject("roMessagePort")
    t.SetMessagePort(port)
    if not t.AsyncGetToString() then return invalid
    msg = Wait(timeoutMs, port)
    if Type(msg) <> "roUrlEvent" then
        t.AsyncCancel()
        return invalid
    end if
    if msg.GetResponseCode() < 200 or msg.GetResponseCode() > 299 then return invalid
    return ParseJson(msg.GetString())
end function

function PostJson(url as String, body as Object, timeoutMs as Integer) as Dynamic
    t = Transfer(url)
    t.AddHeader("Content-Type", "application/json")
    port = CreateObject("roMessagePort")
    t.SetMessagePort(port)
    if not t.AsyncPostFromString(FormatJson(body)) then return invalid
    msg = Wait(timeoutMs, port)
    if Type(msg) <> "roUrlEvent" then
        t.AsyncCancel()
        return invalid
    end if
    if msg.GetResponseCode() < 200 or msg.GetResponseCode() > 299 then return invalid
    parsed = ParseJson(msg.GetString())
    if parsed = invalid then return {}
    return parsed
end function

function Install(req as Object) as Object
    base = TransportUrl(req.url)
    manifest = GetJson(base + "/manifest.json", 30000)
    if manifest = invalid or manifest.id = invalid or manifest.name = invalid then
        return { tag: req.tag, ok: false, error: "That address has no addon manifest." }
    end if
    list = []
    replaced = false
    for each a in Addons()
        if a.transportUrl = base then
            list.Push({ transportUrl: base, manifest: TrimManifest(manifest), enabled: true })
            replaced = true
        else
            list.Push(a)
        end if
    end for
    if not replaced then list.Push({ transportUrl: base, manifest: TrimManifest(manifest), enabled: true })
    SaveAddons(list)
    return { tag: req.tag, ok: true, name: manifest.name }
end function

' TorrServer, as Android's TorrentService and web/src/core/streamingServer.ts: add the magnet
' with its trackers, wait for the file list, choose the file, stream it by its 1-based id.
function TorrServerUrl(req as Object) as Object
    fail = { tag: req.tag, ok: false }
    server = StreamingServer()
    stream = req.stream
    if server = "" or stream.infoHash = invalid then
        fail.error = "Torrent sources need a streaming server. Set one in Settings."
        return fail
    end if
    hash = LCase(stream.infoHash)
    link = "magnet:?xt=urn:btih:" + hash
    if stream.sources <> invalid then
        for each s in stream.sources
            if Left(s, 8) = "tracker:" then link = link + "&tr=" + Mid(s, 9).EncodeUriComponent()
        end for
    end if
    if PostJson(server + "/torrents", { action: "add", link: link, save_to_db: false }, 30000) = invalid then
        fail.error = "The streaming server isn't reachable at " + server + "."
        return fail
    end if
    files = []
    for attempt = 1 to 30
        info = PostJson(server + "/torrents", { action: "get", hash: hash }, 10000)
        if info <> invalid and info.file_stats <> invalid and info.file_stats.Count() > 0 then
            files = info.file_stats
            exit for
        end if
        Sleep(500)
    end for
    if files.Count() = 0 then
        fail.error = "No peers sent this torrent's file list in time."
        return fail
    end if
    index = PickFileIndex(files, stream)
    id = files[index].id
    if id = invalid then id = index + 1
    return { tag: req.tag, ok: true, url: server + "/stream?link=" + link.EncodeUriComponent() + "&index=" + id.ToStr() + "&play" }
end function

' The file named in behaviorHints.filename, then fileIdx, then the largest video file.
function PickFileIndex(files as Object, stream as Object) as Integer
    wanted = ""
    if stream.behaviorHints <> invalid and stream.behaviorHints.filename <> invalid then wanted = LCase(stream.behaviorHints.filename)
    if wanted <> "" then
        for i = 0 to files.Count() - 1
            path = LCase(TextOr(files[i].path))
            if path = wanted or Right(path, Len(wanted) + 1) = "/" + wanted then return i
        end for
    end if
    if stream.fileIdx <> invalid and stream.fileIdx >= 0 and stream.fileIdx < files.Count() then return stream.fileIdx
    video = CreateObject("roRegex", "\.(mkv|mp4|m4v|avi|mov|webm|wmv|ts|m2ts|mpg|mpeg)$", "i")
    best = -1
    for i = 0 to files.Count() - 1
        if video.IsMatch(TextOr(files[i].path)) then
            if best < 0 or files[i].length > files[best].length then best = i
        end if
    end for
    if best < 0 then return 0
    return best
end function

function TextOr(value as Dynamic) as String
    if value = invalid then return ""
    return value.ToStr()
end function
