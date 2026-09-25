sub init()
    m.keyboard = m.top.FindNode("keyboard")
    m.results = m.top.FindNode("results")
    m.keyboard.ObserveField("text", "OnText")
    m.results.ObserveField("rowItemSelected", "OnItem")
    ' Search after typing pauses, not on every key.
    m.timer = CreateObject("roSGNode", "Timer")
    m.timer.duration = 0.6
    m.timer.ObserveField("fire", "Search")
end sub

sub onParams()
    m.keyboard.SetFocus(true)
end sub

sub onResumed()
end sub

sub OnText()
    m.timer.control = "stop"
    m.timer.control = "start"
end sub

sub Search()
    query = m.keyboard.text.Trim()
    if query = "" then return
    m.root = CreateObject("roSGNode", "ContentNode")
    m.pending = {}
    for each kind in ["movie", "series"]
        row = m.root.CreateChild("ContentNode")
        if kind = "movie" then row.title = "Movies" else row.title = "Series"
        task = CreateObject("roSGNode", "HttpTask")
        m.pending[kind] = { task: task, row: row }
        task.ObserveField("response", "OnResults")
        task.request = { kind: "json", tag: kind, url: "https://v3-cinemeta.strem.io/catalog/" + kind + "/top/search=" + query.EncodeUriComponent() + ".json" }
        task.control = "run"
    end for
    m.results.content = m.root
end sub

sub OnResults(event as Object)
    res = event.GetData()
    entry = m.pending[res.tag]
    if entry = invalid then return
    if res.ok and res.data <> invalid and res.data.metas <> invalid then
        for each meta in res.data.metas
            item = entry.row.CreateChild("ContentNode")
            item.title = meta.name
            if meta.poster <> invalid then item.HDPosterUrl = meta.poster
            item.AddFields({ metaId: meta.id, metaType: meta.type, addonBase: "https://v3-cinemeta.strem.io" })
        end for
    end if
end sub

sub OnItem()
    picked = m.results.rowItemSelected
    item = m.results.content.GetChild(picked[0]).GetChild(picked[1])
    m.top.navigate = { to: "DetailsView", params: { type: item.metaType, id: item.metaId, addonBase: item.addonBase } }
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if key = "right" and m.keyboard.IsInFocusChain() and m.root <> invalid then
        m.results.SetFocus(true)
        return true
    else if key = "left" and m.results.HasFocus() then
        m.keyboard.SetFocus(true)
        return true
    end if
    return false
end function
