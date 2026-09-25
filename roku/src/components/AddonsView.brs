sub init()
    m.list = m.top.FindNode("list")
    m.list.ObserveField("itemSelected", "OnSelect")
end sub

sub onParams()
    Render()
    m.list.SetFocus(true)
end sub

sub onResumed()
end sub

sub Render()
    m.addons = Addons()
    content = CreateObject("roSGNode", "ContentNode")
    add = content.CreateChild("ContentNode")
    add.title = "+ Install an addon by address…"
    for each a in m.addons
        item = content.CreateChild("ContentNode")
        state = ""
        if a.enabled = false then state = "  (disabled)"
        item.title = a.manifest.name + state
    end for
    m.list.content = content
end sub

sub OnSelect()
    i = m.list.itemSelected
    if i = 0 then
        dialog = CreateObject("roSGNode", "KeyboardDialog")
        dialog.title = "Addon address (…/manifest.json)"
        dialog.buttons = ["Install", "Cancel"]
        dialog.ObserveField("buttonSelected", "OnInstallDialog")
        m.top.GetScene().dialog = dialog
        m.dialog = dialog
        return
    end if
    m.selected = i - 1
    dialog = CreateObject("roSGNode", "Dialog")
    dialog.title = m.addons[m.selected].manifest.name
    dialog.buttons = ["Move up", "Move down", "Enable / disable", "Remove", "Cancel"]
    dialog.ObserveField("buttonSelected", "OnAddonDialog")
    m.top.GetScene().dialog = dialog
    m.dialog = dialog
end sub

sub OnInstallDialog()
    url = m.dialog.text
    choice = m.dialog.buttonSelected
    m.dialog.close = true
    if choice <> 0 or url.Trim() = "" then return
    m.installer = CreateObject("roSGNode", "HttpTask")
    m.installer.ObserveField("response", "OnInstalled")
    m.installer.request = { kind: "install", url: url }
    m.installer.control = "run"
end sub

sub OnInstalled(event as Object)
    res = event.GetData()
    Render()
    dialog = CreateObject("roSGNode", "Dialog")
    if res.ok then dialog.title = "Installed " + res.name else dialog.title = "Couldn't install: " + res.error
    dialog.buttons = ["OK"]
    dialog.ObserveField("buttonSelected", "CloseDialog")
    m.top.GetScene().dialog = dialog
    m.dialog = dialog
end sub

sub CloseDialog()
    m.dialog.close = true
end sub

sub OnAddonDialog()
    choice = m.dialog.buttonSelected
    m.dialog.close = true
    list = m.addons
    i = m.selected
    if choice = 0 and i > 0 then
        item = list[i]
        list[i] = list[i - 1]
        list[i - 1] = item
    else if choice = 1 and i < list.Count() - 1 then
        item = list[i]
        list[i] = list[i + 1]
        list[i + 1] = item
    else if choice = 2 then
        if list[i].enabled = true then list[i].enabled = false else list[i].enabled = true
    else if choice = 3 then
        list.Delete(i)
    else
        return
    end if
    SaveAddons(list)
    Render()
end sub
