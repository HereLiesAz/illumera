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
    content = CreateObject("roSGNode", "ContentNode")
    server = StreamingServer()
    item = content.CreateChild("ContentNode")
    if server = "" then item.title = "Streaming server: not set" else item.title = "Streaming server: " + server
    m.list.content = content
end sub

sub OnSelect()
    dialog = CreateObject("roSGNode", "KeyboardDialog")
    dialog.title = "Streaming server, e.g. http://192.168.1.20:8090 (empty to clear)"
    dialog.text = StreamingServer()
    dialog.buttons = ["Save", "Cancel"]
    dialog.ObserveField("buttonSelected", "OnDialog")
    m.top.GetScene().dialog = dialog
    m.dialog = dialog
end sub

sub OnDialog()
    choice = m.dialog.buttonSelected
    value = m.dialog.text.Trim()
    m.dialog.close = true
    if choice <> 0 then return
    while Right(value, 1) = "/"
        value = Left(value, Len(value) - 1)
    end while
    if value <> "" and LCase(Left(value, 4)) <> "http" then value = "http://" + value
    StoreSet("streamingServer", value)
    Render()
end sub
