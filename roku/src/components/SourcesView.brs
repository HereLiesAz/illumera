sub init()
    m.list = m.top.FindNode("list")
    m.status = m.top.FindNode("status")
    m.list.ObserveField("itemSelected", "OnSelect")
    m.raw = []
    m.tasks = {}
end sub

sub onParams()
    p = m.top.params
    heading = p.meta.name
    if p.video <> invalid then heading = heading + " · S" + Str(p.video.season).Trim() + " · E" + Str(p.video.episode).Trim()
    m.top.FindNode("heading").text = heading
    for each addon in EnabledAddons()
        if Supports(addon, "stream") and AcceptsId(addon, p.videoId) then
            task = CreateObject("roSGNode", "HttpTask")
            tag = Str(m.tasks.Count()).Trim()
            m.tasks[tag] = { task: task, addon: addon }
            task.ObserveField("response", "OnStreams")
            task.request = { kind: "json", tag: tag, url: addon.transportUrl + "/stream/" + EncodeId(p.mediaType) + "/" + EncodeId(p.videoId) + ".json" }
            task.control = "run"
        end if
    end for
    m.waiting = m.tasks.Count()
    Render()
end sub

sub onResumed()
    m.list.SetFocus(true)
end sub

sub OnStreams(event as Object)
    res = event.GetData()
    entry = m.tasks[res.tag]
    if entry = invalid then return
    m.waiting = m.waiting - 1
    if res.ok and res.data <> invalid and res.data.streams <> invalid then
        for each s in res.data.streams
            s.addonBase = entry.addon.transportUrl
            s.addonName = entry.addon.manifest.name
            m.raw.Push(s)
        end for
    end if
    Render()
end sub

sub Render()
    m.sorted = SortStreams(m.raw, m.top.params.mediaType, AddonOrder())
    content = CreateObject("roSGNode", "ContentNode")
    for each s in m.sorted
        item = content.CreateChild("ContentNode")
        label = s.description
        if label = invalid then label = s.title
        if label = invalid then label = s.name
        if label = invalid then label = "Source"
        ' One line per source: the list shows the first line of multi-line titles.
        item.title = CreateObject("roRegex", "\s*\n\s*", "").ReplaceAll(label, " · ") + "   [" + s.addonName + "]"
    end for
    focused = m.list.itemFocused
    m.list.content = content
    if focused <> invalid and focused > 0 and focused < content.GetChildCount() then m.list.jumpToItem = focused
    if m.waiting > 0 then
        m.status.text = "Asking addons… " + Str(m.raw.Count()).Trim() + " sources so far."
    else if m.sorted.Count() = 0 and m.raw.Count() > 0 then
        m.status.text = Str(m.raw.Count()).Trim() + " sources, all hidden by the source filters."
    else if m.sorted.Count() = 0 then
        m.status.text = "No sources found."
    else
        m.status.text = Str(m.sorted.Count()).Trim() + " sources"
    end if
    if m.sorted.Count() > 0 and not m.list.HasFocus() then m.list.SetFocus(true)
end sub

sub OnSelect()
    p = m.top.params
    m.top.navigate = { to: "PlayerView", params: { streams: m.sorted, index: m.list.itemSelected, meta: p.meta, video: p.video, videoId: p.videoId, mediaType: p.mediaType } }
end sub
