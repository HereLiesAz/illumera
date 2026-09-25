sub init()
    m.video = m.top.FindNode("video")
    m.message = m.top.FindNode("message")
    m.video.ObserveField("state", "OnState")
    m.video.ObserveField("position", "OnPosition")
    m.video.ObserveField("duration", "OnDuration")
    m.lastSave = 0
end sub

sub onParams()
    m.index = m.top.params.index
    m.resumeAt = invalid
    saved = Progress()[m.top.params.meta.id]
    if saved <> invalid and saved.videoId = m.top.params.videoId then m.resumeAt = saved.time
    LoadSubtitlesThenPlay()
end sub

sub onResumed()
end sub

sub Say(text as String)
    m.message.text = text
end sub

' Stremio subtitles for this video, gathered before playback (Roku reads tracks at start).
sub LoadSubtitlesThenPlay()
    m.subtitles = []
    m.subtitleTasks = 0
    p = m.top.params
    seriesId = p.videoId.Split(":")[0]
    for each addon in EnabledAddons()
        if Supports(addon, "subtitles") and AcceptsId(addon, seriesId) then
            task = CreateObject("roSGNode", "HttpTask")
            task.ObserveField("response", "OnSubtitles")
            task.request = { kind: "json", url: addon.transportUrl + "/subtitles/" + EncodeId(p.mediaType) + "/" + EncodeId(p.videoId) + ".json" }
            task.control = "run"
            m.subtitleTasks = m.subtitleTasks + 1
        end if
    end for
    if m.subtitleTasks = 0 then
        Start()
        return
    end if
    ' Don't hold playback hostage to a slow subtitle addon.
    m.subtitleTimer = CreateObject("roSGNode", "Timer")
    m.subtitleTimer.duration = 5
    m.subtitleTimer.ObserveField("fire", "Start")
    m.subtitleTimer.control = "start"
    Say("Finding subtitles…")
end sub

sub OnSubtitles(event as Object)
    res = event.GetData()
    if res.ok and res.data <> invalid and res.data.subtitles <> invalid then
        for each s in res.data.subtitles
            if s.url <> invalid then m.subtitles.Push(s)
        end for
    end if
    m.subtitleTasks = m.subtitleTasks - 1
    if m.subtitleTasks = 0 then Start()
end sub

sub Start()
    if m.started = true then return
    m.started = true
    if m.subtitleTimer <> invalid then m.subtitleTimer.control = "stop"
    PlayCurrent()
end sub

function Playable(stream as Object) as Boolean
    if stream.url <> invalid then return LCase(Left(stream.url, 4)) = "http"
    return stream.infoHash <> invalid and StreamingServer() <> ""
end function

sub PlayCurrent()
    streams = m.top.params.streams
    while m.index < streams.Count() and not Playable(streams[m.index])
        m.index = m.index + 1
    end while
    if m.index >= streams.Count() then
        Say("No playable source left. Press Back.")
        return
    end if
    stream = streams[m.index]
    if stream.url = invalid then
        Say("Adding the torrent to the streaming server…")
        m.torrent = CreateObject("roSGNode", "HttpTask")
        m.torrent.ObserveField("response", "OnTorrent")
        m.torrent.request = { kind: "torrent", stream: stream }
        m.torrent.control = "run"
        return
    end if
    PlayUrl(stream, stream.url)
end sub

sub OnTorrent(event as Object)
    res = event.GetData()
    if not res.ok then
        Fallback(res.error)
        return
    end if
    PlayUrl(m.top.params.streams[m.index], res.url)
end sub

sub PlayUrl(stream as Object, url as String)
    Say("")
    content = CreateObject("roSGNode", "ContentNode")
    content.url = url
    content.title = m.top.params.meta.name
    lower = LCase(url)
    if Instr(1, lower, ".m3u8") > 0 or Instr(1, lower, "/hls") > 0 then
        content.streamFormat = "hls"
    else if Instr(1, lower, ".mpd") > 0 then
        content.streamFormat = "dash"
    else if Instr(1, lower, ".mkv") > 0 then
        content.streamFormat = "mkv"
    end if
    tracks = []
    own = stream.subtitles
    if own = invalid then own = []
    for each s in own
        if s.url <> invalid then tracks.Push({ TrackName: s.url, Language: LangOr(s.lang), Description: LangOr(s.lang) })
    end for
    for each s in m.subtitles
        tracks.Push({ TrackName: s.url, Language: LangOr(s.lang), Description: LangOr(s.lang) })
    end for
    content.SubtitleTracks = tracks
    headers = invalid
    if stream.behaviorHints <> invalid and stream.behaviorHints.proxyHeaders <> invalid then headers = stream.behaviorHints.proxyHeaders.request
    if headers <> invalid then
        list = []
        for each name in headers
            list.Push(name + ":" + headers[name])
        end for
        content.HttpHeaders = list
    end if
    m.video.content = content
    if m.resumeAt <> invalid and m.resumeAt > 5 then m.video.seek = m.resumeAt
    m.video.control = "play"
    m.video.SetFocus(true)
end sub

function LangOr(lang as Dynamic) as String
    if lang = invalid then return "und"
    return lang
end function

sub Fallback(reason as Dynamic)
    text = "This source failed to play."
    if reason <> invalid then text = reason
    m.index = m.index + 1
    m.resumeAt = invalid
    Say(text + " Trying the next source.")
    PlayCurrent()
end sub

sub OnState()
    if m.video.state = "error" then Fallback(invalid)
    if m.video.state = "finished" then Save()
end sub

' A "movie" under 4.5 minutes (1.5 for episodes) is a placeholder clip, not the real thing.
sub OnDuration()
    d = m.video.duration
    limit = 90
    if m.top.params.mediaType = "movie" then limit = 270
    if d > 0 and d < limit then
        m.video.control = "stop"
        Fallback("This source is only a placeholder clip.")
    end if
end sub

sub OnPosition()
    if m.video.position - m.lastSave >= 10 or m.video.position < m.lastSave then Save()
end sub

sub Save()
    if m.video.duration <= 0 then return
    m.lastSave = m.video.position
    meta = m.top.params.meta
    SaveProgress({ type: meta.type, id: meta.id, name: meta.name, poster: meta.poster, videoId: m.top.params.videoId, time: m.video.position, duration: m.video.duration, updatedAt: CreateObject("roDateTime").AsSeconds() })
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if press and key = "back" then
        Save()
        m.video.control = "stop"
    end if
    ' Back continues to the scene, which closes this view.
    return false
end function
