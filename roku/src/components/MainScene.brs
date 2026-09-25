sub init()
    m.views = m.top.FindNode("views")
    m.stack = []
    m.top.backgroundColor = "0x000000FF"
    m.top.backgroundUri = ""
    EnsureDefaults()
end sub

' First run: Cinemeta for catalogs and details, OpenSubtitles for subtitles.
sub EnsureDefaults()
    if Addons().Count() > 0 then
        Open("HomeView", {})
        return
    end if
    m.pending = ["https://v3-cinemeta.strem.io", "https://opensubtitles-v3.strem.io"]
    InstallNext()
end sub

sub InstallNext()
    if m.pending.Count() = 0 then
        Open("HomeView", {})
        return
    end if
    m.installer = CreateObject("roSGNode", "HttpTask")
    m.installer.ObserveField("response", "OnDefaultInstalled")
    m.installer.request = { kind: "install", url: m.pending.Shift() }
    m.installer.control = "run"
end sub

sub OnDefaultInstalled()
    InstallNext()
end sub

sub Open(name as String, params as Object)
    if m.stack.Count() > 0 then m.stack.Peek().visible = false
    view = CreateObject("roSGNode", name)
    view.ObserveField("navigate", "OnNavigate")
    m.views.AppendChild(view)
    m.stack.Push(view)
    view.params = params
    view.SetFocus(true)
end sub

sub OnNavigate(event as Object)
    nav = event.GetData()
    if nav = invalid or nav.to = invalid then return
    if nav.to = "back" then
        Back()
    else
        Open(nav.to, nav.params)
    end if
end sub

function Back() as Boolean
    if m.stack.Count() <= 1 then return false
    view = m.stack.Pop()
    m.views.RemoveChild(view)
    top = m.stack.Peek()
    top.visible = true
    top.resumed = true
    top.SetFocus(true)
    return true
end function

function onKeyEvent(key as String, press as Boolean) as Boolean
    if press and key = "back" then return Back()
    return false
end function
