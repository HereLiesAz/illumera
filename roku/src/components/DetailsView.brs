sub init()
    m.play = m.top.FindNode("play")
    m.seasons = m.top.FindNode("seasons")
    m.episodes = m.top.FindNode("episodes")
    m.status = m.top.FindNode("status")
    m.play.ObserveField("buttonSelected", "OnPlay")
    m.seasons.ObserveField("itemFocused", "OnSeason")
    m.episodes.ObserveField("itemSelected", "OnEpisode")
end sub

sub onParams()
    p = m.top.params
    m.candidates = []
    if p.addonBase <> invalid and p.addonBase <> "" then m.candidates.Push(p.addonBase)
    ' Then meta addons that accept the type, then Cinemeta; only the origin may answer a different id.
    for each addon in EnabledAddons()
        if Supports(addon, "meta") and addon.transportUrl <> p.addonBase then m.candidates.Push(addon.transportUrl)
    end for
    m.candidates.Push("https://v3-cinemeta.strem.io")
    m.tried = 0
    TryNext()
end sub

sub onResumed()
end sub

sub TryNext()
    if m.tried >= m.candidates.Count() then
        m.status.text = "Couldn't load this title."
        return
    end if
    base = m.candidates[m.tried]
    m.tried = m.tried + 1
    m.task = CreateObject("roSGNode", "HttpTask")
    m.task.ObserveField("response", "OnMeta")
    m.task.request = { kind: "json", url: base + "/meta/" + EncodeId(m.top.params.type) + "/" + EncodeId(m.top.params.id) + ".json" }
    m.task.control = "run"
end sub

sub OnMeta(event as Object)
    res = event.GetData()
    meta = invalid
    if res.ok and res.data <> invalid then meta = res.data.meta
    origin = m.tried = 1 and m.top.params.addonBase <> invalid and m.top.params.addonBase <> ""
    if meta = invalid or meta.name = invalid or (not origin and meta.id <> m.top.params.id) then
        TryNext()
        return
    end if
    Show(meta)
end sub

sub Show(meta as Object)
    m.meta = meta
    m.status.visible = false
    m.top.FindNode("title").text = meta.name
    if meta.background <> invalid then m.top.FindNode("backdrop").uri = meta.background
    facts = []
    for each f in [meta.releaseInfo, meta.runtime]
        if f <> invalid and f <> "" then facts.Push(f)
    end for
    if meta.imdbRating <> invalid and meta.imdbRating <> "" then facts.Push("IMDb " + meta.imdbRating)
    m.top.FindNode("facts").text = facts.Join("   ")
    if meta.description <> invalid then m.top.FindNode("description").text = meta.description

    m.byseason = {}
    seasons = []
    if meta.videos <> invalid then
        for each v in meta.videos
            if v.season <> invalid and v.episode <> invalid then
                key = Str(v.season).Trim()
                if m.byseason[key] = invalid then
                    m.byseason[key] = []
                    seasons.Push(v.season)
                end if
                m.byseason[key].Push(v)
            end if
        end for
    end if
    if seasons.Count() = 0 then
        m.play.visible = true
        m.play.SetFocus(true)
        return
    end if
    seasons.Sort()
    ' Specials (season 0) go last.
    if seasons[0] = 0 then seasons.Push(seasons.Shift())
    m.seasonNumbers = seasons
    content = CreateObject("roSGNode", "ContentNode")
    for each s in seasons
        item = content.CreateChild("ContentNode")
        if s = 0 then item.title = "Specials" else item.title = "Season " + Str(s).Trim()
    end for
    m.seasons.content = content
    m.seasons.visible = true
    m.episodes.visible = true
    OnSeason()
    m.seasons.SetFocus(true)
end sub

sub OnSeason()
    if m.seasonNumbers = invalid then return
    season = m.seasonNumbers[m.seasons.itemFocused]
    list = m.byseason[Str(season).Trim()]
    list.SortBy("episode")
    m.current = list
    content = CreateObject("roSGNode", "ContentNode")
    for each v in list
        item = content.CreateChild("ContentNode")
        title = v.title
        if title = invalid then title = v.name
        if title = invalid then title = ""
        item.title = Str(v.episode).Trim() + ". " + title
    end for
    m.episodes.content = content
end sub

sub OnPlay()
    OpenSources(invalid)
end sub

sub OnEpisode()
    OpenSources(m.current[m.episodes.itemSelected])
end sub

sub OpenSources(video as Dynamic)
    videoId = m.meta.id
    mediaType = m.meta.type
    if video <> invalid then
        mediaType = "series"
        videoId = video.id
        if videoId = invalid or videoId = "" then videoId = m.meta.id + ":" + Str(video.season).Trim() + ":" + Str(video.episode).Trim()
    end if
    m.top.navigate = { to: "SourcesView", params: { meta: { id: m.meta.id, type: m.meta.type, name: m.meta.name, poster: m.meta.poster }, video: video, videoId: videoId, mediaType: mediaType } }
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if key = "right" and m.seasons.HasFocus() then
        m.episodes.SetFocus(true)
        return true
    else if key = "left" and m.episodes.HasFocus() then
        m.seasons.SetFocus(true)
        return true
    end if
    return false
end function
