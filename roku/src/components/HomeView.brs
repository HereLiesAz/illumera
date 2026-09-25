sub init()
    m.menu = m.top.FindNode("menu")
    m.menu.buttons = ["Search", "Addons", "Settings"]
    m.menu.ObserveField("buttonSelected", "OnMenu")
    m.rows = m.top.FindNode("rows")
    m.rows.ObserveField("rowItemSelected", "OnItem")
    m.tasks = {}
end sub

sub onParams()
    Load()
end sub

' Back from Addons or a title: catalogs and progress may have changed.
sub onResumed()
    Load()
end sub

sub Load()
    m.root = CreateObject("roSGNode", "ContentNode")
    m.catalogRows = []
    AddContinueWatching()
    for each addon in EnabledAddons()
        if Supports(addon, "catalog") and addon.manifest.catalogs <> invalid then
            for each c in addon.manifest.catalogs
                if not HasRequiredExtra(c) then
                    row = m.root.CreateChild("ContentNode")
                    name = c.name
                    if name = invalid then name = c.id
                    row.title = name + " · " + c.type
                    url = addon.transportUrl + "/catalog/" + EncodeId(c.type) + "/" + EncodeId(c.id) + ".json"
                    Fetch(row, url, addon.transportUrl)
                end if
            end for
        end if
    end for
    m.rows.content = m.root
    m.rows.SetFocus(true)
end sub

function HasRequiredExtra(catalog as Object) as Boolean
    if catalog.extra = invalid then return false
    for each e in catalog.extra
        if e.isRequired = true then return true
    end for
    return false
end function

sub AddContinueWatching()
    list = []
    all = Progress()
    for each id in all
        p = all[id]
        if p.duration > 0 and p.time > 30 and p.time / p.duration < 0.92 then list.Push(p)
    end for
    if list.Count() = 0 then return
    list.SortBy("updatedAt", "r")
    row = m.root.CreateChild("ContentNode")
    row.title = "Continue watching"
    for each p in list
        item = row.CreateChild("ContentNode")
        item.title = p.name
        item.HDPosterUrl = p.poster
        item.rating = Str(p.time / p.duration).Trim()
        item.AddFields({ metaId: p.id, metaType: p.type, addonBase: "" })
    end for
end sub

sub Fetch(row as Object, url as String, addonBase as String)
    task = CreateObject("roSGNode", "HttpTask")
    tag = Str(m.tasks.Count()).Trim()
    m.tasks[tag] = { task: task, row: row, addonBase: addonBase }
    task.ObserveField("response", "OnCatalog")
    task.request = { kind: "json", url: url, tag: tag }
    task.control = "run"
end sub

sub OnCatalog(event as Object)
    res = event.GetData()
    entry = m.tasks[res.tag]
    if entry = invalid then return
    m.tasks.Delete(res.tag)
    row = entry.row
    if not res.ok or res.data = invalid or res.data.metas = invalid or res.data.metas.Count() = 0 then
        m.root.RemoveChild(row)
        return
    end if
    for each meta in res.data.metas
        if meta.id <> invalid and meta.name <> invalid and meta.type <> invalid then
            item = row.CreateChild("ContentNode")
            item.title = meta.name
            if meta.poster <> invalid then item.HDPosterUrl = meta.poster
            item.AddFields({ metaId: meta.id, metaType: meta.type, addonBase: entry.addonBase })
        end if
    end for
end sub

sub OnItem()
    picked = m.rows.rowItemSelected
    item = m.rows.content.GetChild(picked[0]).GetChild(picked[1])
    m.top.navigate = { to: "DetailsView", params: { type: item.metaType, id: item.metaId, addonBase: item.addonBase } }
end sub

sub OnMenu()
    views = ["SearchView", "AddonsView", "SettingsView"]
    m.top.navigate = { to: views[m.menu.buttonSelected], params: {} }
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    focused = m.rows.rowItemFocused
    if key = "up" and m.rows.HasFocus() and (focused = invalid or focused[0] = 0) then
        m.menu.SetFocus(true)
        return true
    else if key = "down" and m.menu.IsInFocusChain() then
        m.rows.SetFocus(true)
        return true
    end if
    return false
end function
