extends Node2D
const Game=preload("res://scripts/game.gd")
const Miso=preload("res://scripts/miso_sprite.gd")
const Occlusion=preload("res://scripts/occlusion.gd")
const INK:=Color("39493f")
const CREAM:=Color("fff8e9")
const GREEN:=Color("41644f")
var environment: Dictionary
var game: RefCounted
var masker: RefCounted
var textures: Dictionary={}
var hit_images: Dictionary={}
var actor:=Node2D.new()
var sprite: Sprite2D
var shadow:=Polygon2D.new()
var camera:=Camera2D.new()
var hud:=CanvasLayer.new()
var ui:=Control.new()
var sheet: PanelContainer
var sheet_column: VBoxContainer
var status_label: Label
var activity_label: Label
var shade:=CanvasModulate.new()
var dust: CPUParticles2D
var hearts: CPUParticles2D
var target_marker:=Line2D.new()
var ambient_nodes: Array[Dictionary]=[]
var density:=128
var effects:=true
var reduced:=false
var night:=false
var paused:=false
var native_host: Object
var diagnostics:=false
var system_reduced:=false
var is_ready:=false
var first_action_at:=-1.0
var saved_revision:=0
var report_time:=0.0
var state_error:=false
var frames:=0
var menu_id:=""
var opening_ticks:=0

func _ready() -> void:
	opening_ticks=Time.get_ticks_msec()
	texture_filter=CanvasItem.TEXTURE_FILTER_NEAREST
	DisplayServer.screen_set_keep_on(true)
	Engine.max_fps=30
	environment=JSON.parse_string(FileAccess.get_file_as_string("res://assets/environment.json"))
	game=Game.new(environment)
	_load_settings()
	if native_host:diagnostics=native_host.diagnostics_enabled()
	_build_room()
	_build_particles()
	_build_ui()
	get_viewport().size_changed.connect(_layout_ui)
	get_tree().auto_accept_quit=false
	get_tree().quit_on_go_back=false
	if native_host:
		native_host.connect("host_command",_host_command)
	_layout_ui()
	_draw_actor()
	shade.color=Color("d9c5ac") if night else Color.WHITE
	dust.emitting=effects and not (reduced or system_reduced)
	await RenderingServer.frame_post_draw
	is_ready=true
	_report()
	print("FOLIO_CAFE_READY version=0.19.0 engine=4.7.2 activities=21 facings=8")
	var args:=OS.get_cmdline_user_args()
	if "--review" in args:
		_review(args)

func _load_settings() -> void:
	var source: Dictionary={}
	if Engine.has_singleton("FolioCafe"):
		native_host=Engine.get_singleton("FolioCafe")
		var decoded=JSON.parse_string(native_host.load_state())
		if decoded is Dictionary:source=decoded
	elif FileAccess.file_exists("user://folio-miso-home-v1.json"):
		var decoded=JSON.parse_string(FileAccess.get_file_as_string("user://folio-miso-home-v1.json"))
		if decoded is Dictionary:source=decoded
	_apply_settings(source,false)

func _apply_settings(source: Dictionary,persist: bool=true) -> void:
	if source.get("mode","") in Game.MODES:game.mode=source.mode
	if source.get("pace","") in Game.PACES:game.pace=source.pace
	var pixels=source.get("density",128)
	if (pixels is int or pixels is float) and (pixels==96 or pixels==128):density=int(pixels)
	if source.get("ambient",true) is bool:effects=source.get("ambient",true)
	if source.get("reduced",false) is bool:reduced=source.get("reduced",false)
	if source.get("systemReduced",false) is bool:system_reduced=source.get("systemReduced",false)
	if source.get("night",false) is bool:night=source.get("night",false)
	if source.get("counts",{}) is Dictionary:
		for id in Game.ACTIONS:
			var value=source.get("counts",{}).get(id,0)
			if (value is int or value is float) and is_finite(float(value)) and float(value)==floorf(float(value)) and value>=0 and value<=100000:
				if value>0:game.counts[id]=maxi(int(game.counts.get(id,0)),int(value))
	if is_instance_valid(sprite):sprite.set_density(density)
	if is_instance_valid(dust):dust.emitting=effects and not (reduced or system_reduced)
	shade.color=Color("d9c5ac") if night else Color.WHITE
	if persist:_save()

func _settings() -> Dictionary:
	return {"version":1,"mode":game.mode,"pace":game.pace,"density":density,"ambient":effects,"reduced":reduced,"night":night,"counts":game.counts.duplicate()}

func _save() -> void:
	if not is_instance_valid(game):return
	var value:=JSON.stringify(_settings())
	if native_host:
		state_error=not native_host.save_state(value)
	else:
		var file:=FileAccess.open("user://folio-miso-home-v1.json",FileAccess.WRITE)
		if file:file.store_string(value);file.flush()
		state_error=file==null
	saved_revision=game.revision
	_report()

func _build_room() -> void:
	for prop in environment.assets:
		textures[prop.sprite]=load(prop.sprite)
		var hit: Image=textures[prop.sprite].get_image()
		hit.resize(int(prop.cell[0]),int(prop.cell[1]),Image.INTERPOLATE_NEAREST)
		hit_images[prop.id]=hit
	for tile in environment.tiles:textures[tile.path]=load(tile.path)
	for i in range(5):
		_tile("foundation_left",Vector2(i,5));_tile("foundation_right",Vector2(5,i))
	for y in range(5):
		for x in range(5):_tile(["floor_a","floor_b","floor_c"][(x*3+y*7)%3],Vector2(x,y))
	for i in range(5):_tile("wall_left",Vector2(0,i));_tile("wall_right",Vector2(i,0))
	for p in [Vector2(0,0),Vector2(0,5),Vector2(5,0)]:_tile("wall_post",p)
	var furniture:=Node2D.new()
	furniture.y_sort_enabled=true
	add_child(furniture)
	var ambience: Dictionary=JSON.parse_string(FileAccess.get_file_as_string("res://assets/ambience.json"))
	for prop in environment.assets:
		var root:=Node2D.new()
		root.position=Game.project(Vector2(prop.pos[0],prop.pos[1]))
		root.z_index=-3 if prop.get("flat",false) else -2 if prop.surface!="floor" else -1
		var top:=-Vector2(prop.anchor[0],prop.anchor[1]+prop.elevation)
		var image:=Sprite2D.new()
		image.texture=load("res://assets/ambient-base-%s.png"%prop.id) if prop.id in ambience else textures[prop.sprite]
		image.centered=false
		image.scale=Vector2(prop.cell[0],prop.cell[1])/image.texture.get_size()
		image.position=top
		root.add_child(image)
		if prop.id in ambience:
			for i in range(ambience[prop.id].size()):
				var part: Dictionary=ambience[prop.id][i]
				var pivot:=Node2D.new()
				pivot.position=top+Vector2(part.pivot[0],part.pivot[1])
				var leaf:=Sprite2D.new()
				leaf.texture=load("res://assets/ambient-%s-%d.png"%[prop.id,i])
				leaf.centered=false;leaf.scale=Vector2(.5,.5)
				leaf.position=Vector2(part.box[0]-part.pivot[0],part.box[1]-part.pivot[1])
				pivot.add_child(leaf);root.add_child(pivot)
				ambient_nodes.append({"node":pivot,"amplitude":part.amplitude,"period":part.period})
		furniture.add_child(root)
	furniture.add_child(actor)
	var outline:=PackedVector2Array()
	for i in range(24):outline.append(Vector2(cos(i*TAU/24.0)*22,sin(i*TAU/24.0)*7))
	shadow.polygon=outline;shadow.color=Color(.2,.14,.1,.16)
	actor.add_child(shadow)
	sprite=Miso.new();sprite.set_density(density)
	masker=Occlusion.new();sprite.material=masker.material
	actor.add_child(sprite)
	add_child(shade)
	add_child(camera)
	camera.zoom=Vector2(2,2);camera.position=Game.project(game.at)-Vector2(0,35)
	add_child(target_marker)
	target_marker.width=1.2;target_marker.default_color=Color("819c79")
	for i in range(25):target_marker.add_point(Vector2(cos(i*TAU/24.0)*9,sin(i*TAU/24.0)*4))
	target_marker.hide()

func _tile(id: String,point: Vector2) -> void:
	for tile in environment.tiles:
		if tile.id!=id:continue
		var image:=Sprite2D.new()
		image.texture=textures[tile.path];image.centered=false
		image.position=Game.project(point)-Vector2(tile.anchor[0],tile.anchor[1])
		image.scale=Vector2(tile.size[0],tile.size[1])/image.texture.get_size();image.z_index=-10
		add_child(image)
		return

func _build_particles() -> void:
	dust=CPUParticles2D.new();dust.amount=12;dust.lifetime=7;dust.preprocess=7
	dust.position=Vector2(420,365);dust.emission_shape=CPUParticles2D.EMISSION_SHAPE_RECTANGLE
	dust.emission_rect_extents=Vector2(30,48);dust.direction=Vector2(.3,1);dust.spread=25
	dust.initial_velocity_min=1;dust.initial_velocity_max=3;dust.gravity=Vector2.ZERO
	dust.color=Color(1,.92,.68,.55);dust.scale_amount_min=.6;dust.scale_amount_max=1.1
	dust.emitting=effects and not (reduced or system_reduced);add_child(dust)
	hearts=CPUParticles2D.new();hearts.texture=load("res://assets/heart.svg")
	hearts.one_shot=true;hearts.emitting=false;hearts.amount=7;hearts.lifetime=1.4;hearts.explosiveness=.9
	hearts.direction=Vector2.UP;hearts.spread=45;hearts.initial_velocity_min=15;hearts.initial_velocity_max=30
	hearts.gravity=Vector2(0,-3);hearts.scale_amount_min=.45;hearts.scale_amount_max=.8;add_child(hearts)

func _process(delta: float) -> void:
	if not is_ready:return
	frames+=1
	var focus:=_camera_focus()
	var moving_camera:=camera.position.distance_to(focus)>1
	# Preserve the fractional follow position until convergence. Rounding every
	# interpolation step can stick above the idle threshold and render forever.
	camera.position=camera.position.lerp(focus,1-exp(-delta*3)) if moving_camera and not (reduced or system_reduced) else focus.round()
	if not paused and not sheet.visible:
		game.update(minf(delta,.25))
		if game.busy and first_action_at<0:
			first_action_at=game.time
			print("FOLIO_CAFE_FIRST_ACTION seconds=",first_action_at)
		if game.revision!=saved_revision:_save()
	_draw_actor()
	for part in ambient_nodes:
		part.node.rotation=sin(game.time*TAU/part.period)*part.amplitude if effects and not (reduced or system_reduced) and not paused and not sheet.visible else 0
	dust.speed_scale=0 if paused or sheet.visible else 1
	hearts.speed_scale=0 if paused else 1
	var animating: bool=game.busy or hearts.emitting or moving_camera
	Engine.max_fps=20 if sheet.visible else 30 if animating and not paused else 2 if reduced or system_reduced or paused else 8
	status_label.text="Ændringer kunne ikke gemmes" if state_error else "En rolig pause" if paused else Game.ACTIONS[game.action][0] if game.action in Game.ACTIONS else "Miso tager en lur" if game.resting=="sleep" else "Et lille hvil mellem siderne"
	activity_label.text="Næste: "+Game.ACTIONS[game.pending.id][0] if game.pending.get("id","") in Game.ACTIONS else "%d af 21 små øjeblikke oplevet"%game.counts.size()
	report_time+=delta
	if report_time>=.4:
		report_time=0
		if diagnostics:_report()

func _camera_focus() -> Vector2:
	var view:=get_viewport_rect().size
	var focus: Vector2=Game.project(game.at,game.z)-Vector2(0,35)
	if sheet.visible:
		if view.x>view.y:focus.x+=view.x*.15/camera.zoom.x
		else:focus.y+=(view.y*.5-sheet.position.y+80)/camera.zoom.y+35
	else:
		var half:=view/camera.zoom/2
		focus.x=clampf(focus.x,112+half.x,912-half.x) if half.x<400 else 512
		focus.y=clampf(focus.y,half.y,800-half.y) if half.y<400 else 400
	return focus

func _draw_actor() -> void:
	actor.position=Game.project(game.at).round()
	sprite.draw_game(game)
	masker.update(game)
	shadow.scale=Vector2.ONE*maxf(.3,1-game.z*.002)
	shadow.position=Vector2(0,-float(game.platforms.get(game.support,{"z":0}).z))
	shadow.visible=game.support!="box" and not (game.occlusion=="tunnel" and game.progress>.2 and game.progress<.8)
	target_marker.visible=game.mark!=Vector2.INF
	if target_marker.visible:target_marker.position=Game.project(game.mark)

func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventMouseButton and event.pressed and event.button_index==MOUSE_BUTTON_LEFT:
		if sheet.visible:sheet.hide();return
		var point:=get_global_mouse_position()
		var prop:=_pick(point)
		if prop!="":_activities(prop)
		else:_command("move",Game.inverse(point))
	if event is InputEventKey and event.pressed and event.keycode==KEY_ESCAPE:_close_or_quit()

func _pick(point: Vector2) -> String:
	var ordered: Array=environment.assets.duplicate()
	ordered.sort_custom(func(a,b):return a.pos[0]+a.pos[1]<b.pos[0]+b.pos[1])
	ordered.reverse()
	for prop in ordered:
		if prop.id=="rug":continue
		var top:=Game.project(Vector2(prop.pos[0],prop.pos[1]),prop.elevation)-Vector2(prop.anchor[0],prop.anchor[1])
		var p:=point-top
		if p.x<0 or p.y<0 or p.x>=prop.cell[0] or p.y>=prop.cell[1]:continue
		var image: Image=hit_images[prop.id]
		var pixel:=Vector2i(p*Vector2(image.get_size())/Vector2(prop.cell[0],prop.cell[1]))
		if image.get_pixelv(pixel).a>.5:return prop.id
	return ""

func _command(id: String,target: Vector2=Vector2.INF) -> void:
	paused=false;game.set_mode("manual")
	game.command(id,target)
	sheet.hide();_save()

func _notification(what: int) -> void:
	if what==NOTIFICATION_WM_GO_BACK_REQUEST or what==NOTIFICATION_WM_CLOSE_REQUEST:_close_or_quit()
	elif what==NOTIFICATION_APPLICATION_PAUSED:_save()

func _close_or_quit() -> void:
	if is_instance_valid(sheet) and sheet.visible:sheet.hide()
	else:_save();get_tree().quit()

func _style(color: Color,radius: int=22) -> StyleBoxFlat:
	var box:=StyleBoxFlat.new();box.bg_color=color;box.set_corner_radius_all(radius)
	box.content_margin_left=20;box.content_margin_right=20;box.content_margin_top=16;box.content_margin_bottom=16
	box.border_color=Color("e6dac2");box.set_border_width_all(1)
	box.shadow_color=Color(.17,.13,.08,.1);box.shadow_size=10;box.shadow_offset=Vector2(0,4)
	return box

func _button(text: String,callback: Callable,primary: bool=false) -> Button:
	var b:=Button.new();b.mouse_filter=Control.MOUSE_FILTER_PASS;b.text=text;b.custom_minimum_size.y=62
	b.add_theme_font_size_override("font_size",18)
	for state in ["font_color","font_hover_color","font_focus_color"]:b.add_theme_color_override(state,CREAM if primary else INK)
	b.add_theme_stylebox_override("normal",_style(GREEN if primary else Color("f2eadb"),16))
	b.add_theme_stylebox_override("hover",_style(Color("557660") if primary else Color("e7dfcf"),16))
	b.add_theme_stylebox_override("pressed",_style(Color("8da58a"),16));b.pressed.connect(callback)
	return b

func _label(text: String,size: int=18,color: Color=INK) -> Label:
	var label:=Label.new();label.text=text;label.add_theme_font_size_override("font_size",size);label.add_theme_color_override("font_color",color)
	return label

func _build_ui() -> void:
	add_child(hud);hud.add_child(ui);ui.mouse_filter=Control.MOUSE_FILTER_IGNORE
	var header:=PanelContainer.new();header.name="Header";header.add_theme_stylebox_override("panel",_style(Color("fff8eedf")))
	ui.add_child(header)
	var head_row:=HBoxContainer.new();head_row.add_theme_constant_override("separation",12);header.add_child(head_row)
	head_row.add_child(_button("‹",_close_or_quit))
	var title:=VBoxContainer.new();head_row.add_child(title);title.add_child(_label("Misos lille hjem",26))
	status_label=_label("Godmorgen, Miso",15,Color("667763"));title.add_child(status_label)
	var nav:=PanelContainer.new();nav.name="Navigation";nav.add_theme_stylebox_override("panel",_style(Color("fff8ee")));ui.add_child(nav)
	var column:=VBoxContainer.new();column.add_theme_constant_override("separation",10);nav.add_child(column)
	activity_label=_label("En lille pause mellem siderne",16,Color("75816c"));activity_label.horizontal_alignment=HORIZONTAL_ALIGNMENT_CENTER;column.add_child(activity_label)
	var row:=HBoxContainer.new();row.add_theme_constant_override("separation",8);column.add_child(row)
	for entry in [["Aktiviteter",func():_activities()],["Miso",_miso_menu],["Værelset",_room_menu]]:
		var button:=_button(entry[0],entry[1],entry[0]=="Aktiviteter");button.size_flags_horizontal=Control.SIZE_EXPAND_FILL;row.add_child(button)
	sheet=PanelContainer.new();sheet.add_theme_stylebox_override("panel",_style(CREAM,26));ui.add_child(sheet);sheet.hide()

func _layout_ui() -> void:
	if not is_instance_valid(sheet):return
	var physical:=DisplayServer.window_get_size()
	var landscape:=physical.x>physical.y
	var base:=Vector2i(960,540) if landscape else Vector2i(540,1200)
	if get_window().content_scale_size!=base:get_window().content_scale_size=base
	var view:=get_viewport_rect().size;ui.size=view
	var header: Control=ui.get_node("Header");header.position=Vector2(20,24);header.size=Vector2(minf(view.x-40,500),90)
	var nav: Control=ui.get_node("Navigation")
	nav.size=Vector2(minf(view.x-40,540),142);nav.position=Vector2((view.x-nav.size.x)/2,view.y-166)
	if landscape:
		nav.position.x=20;nav.size.x=minf(520,view.x*.48)
		sheet.size=Vector2(minf(460,view.x*.45),view.y-48);sheet.position=Vector2(view.x-sheet.size.x-20,24)
	else:
		sheet.size=Vector2(view.x-40,minf(520,view.y-360));sheet.position=Vector2(20,nav.position.y-sheet.size.y-22)

func _show_sheet(title: String,subtitle: String,id: String) -> VBoxContainer:
	for child in sheet.get_children():sheet.remove_child(child);child.queue_free()
	menu_id=id
	var column:=VBoxContainer.new();column.add_theme_constant_override("separation",12);sheet.add_child(column)
	var top:=HBoxContainer.new();column.add_child(top)
	var heading:=_label(title,25);heading.size_flags_horizontal=Control.SIZE_EXPAND_FILL;top.add_child(heading);top.add_child(_button("×",func():sheet.hide()))
	var sub:=_label(subtitle,15,Color("7d826d"));sub.size.x=maxf(180,sheet.size.x-40);sub.autowrap_mode=TextServer.AUTOWRAP_WORD_SMART;column.add_child(sub)
	var scroll:=ScrollContainer.new();scroll.horizontal_scroll_mode=ScrollContainer.SCROLL_MODE_DISABLED;scroll.size_flags_vertical=Control.SIZE_EXPAND_FILL;scroll.follow_focus=true;column.add_child(scroll)
	sheet_column=VBoxContainer.new();sheet_column.size_flags_horizontal=Control.SIZE_EXPAND_FILL;sheet_column.add_theme_constant_override("separation",10);scroll.add_child(sheet_column)
	sheet.show();_layout_ui();_settle_sheet.call_deferred()
	return sheet_column

func _settle_sheet() -> void:
	# Wrapped labels compute height after their container receives its width.
	await get_tree().process_frame
	_layout_ui()
	await get_tree().process_frame
	_layout_ui()

func _activities(prop: String="") -> void:
	var names:={"bowls":"En lille bid","cat_tower":"Til vejrs","cubby_books":"Mellem bøgerne","box":"En god papkasse","tunnel":"Gennem tunnelen","sofa":"En blød plads","window":"Ved vinduet","ball_track":"Leg med bolden","feather":"Fang fjeren","door":"Hvad er der udenfor?"}
	var col:=_show_sheet(names.get(prop,"En stund med Miso"),"Vælg en handling. Miso finder en sikker vej.","activities")
	if prop=="":col.add_child(_button("Gå på opdagelse",func():game.set_mode("random");game.next_activity=0;paused=false;sheet.hide();_save(),true))
	for id in Game.ACTIONS:
		if prop!="" and Game.ACTIONS[id][1]!=prop:continue
		col.add_child(_button(Game.ACTIONS[id][0],func():_command(id)))
	if prop!="":col.add_child(_button("Alle aktiviteter",func():_activities()))
	col.add_child(_button("Ned på gulvet",func():_command("down")))
	col.add_child(_button("Stop efter denne bevægelse",func():_command("stop")))
	col.add_child(_button("Fortsæt" if paused else "Hold en lille pause",_toggle_pause))

func _miso_menu() -> void:
	var col:=_show_sheet("Miso","En venlig lille kat med tid til dig.","miso")
	col.add_child(_button("Giv Miso lidt kærlighed",_pet,true))
	col.add_child(_button("Pelspleje på tæppet",func():_command("groom")))
	col.add_child(_button("En rolig lur",func():_command("sleep")))
	col.add_child(_button("Fine detaljer"+("  ✓" if density==128 else ""),func():density=128;sprite.set_density(128);_save();_miso_menu(),density==128))
	col.add_child(_button("Større pixels"+("  ✓" if density==96 else ""),func():density=96;sprite.set_density(96);_save();_miso_menu(),density==96))

func _room_menu() -> void:
	var col:=_show_sheet("Værelset","Små detaljer, der gør et sted til et hjem.","room")
	col.add_child(_button("Adfærd: "+Game.MODES[game.mode],_modes))
	col.add_child(_button("Tempo: "+{"stroll":"Slentren","walk":"Gang","trot":"Trav","run":"Løb","sprint":"Sprint"}[game.pace],_paces))
	col.add_child(_button("Dagslys" if night else "Tænd aftensolen",func():night=not night;shade.color=Color("d9c5ac") if night else Color.WHITE;_save();_room_menu()))
	col.add_child(_button("Små bevægelser: "+("til" if effects else "fra"),func():effects=not effects;dust.emitting=effects and not (reduced or system_reduced);_save();_room_menu()))
	col.add_child(_button("Reduceret bevægelse: "+("til" if reduced else "fra"),func():reduced=not reduced;dust.emitting=effects and not (reduced or system_reduced);_save();_room_menu()))
	col.add_child(_button("Tilbage til tæppet",func():_command("home")))
	if native_host:col.add_child(_button("Tidligere café og gemte data",func():_save();native_host.open_archive()))
	col.add_child(_button("Tilbage til Folio" if native_host else "Luk Misos hjem",func():_save();get_tree().quit()))

func _modes() -> void:
	var col:=_show_sheet("Misos dag","Lad Miso vælge, eller vælg en lille rutine.","modes")
	for mode in Game.MODES:col.add_child(_button(Game.MODES[mode],func():game.set_mode(mode);paused=false;sheet.hide();_save(),game.mode==mode))

func _paces() -> void:
	var col:=_show_sheet("Tempo","Miso tilpasser sine skridt til tempoet.","paces")
	for value in [["stroll","Slentren"],["walk","Gang"],["trot","Trav"],["run","Løb"],["sprint","Sprint"]]:
		col.add_child(_button(value[1],func():game.pace=value[0];_save();_room_menu(),game.pace==value[0]))

func _pet() -> void:
	sheet.hide();hearts.position=Game.project(game.at,game.z)-Vector2(0,65);hearts.restart()
	game.notice="Miso nyder dit selskab"

func _toggle_pause() -> void:
	paused=not paused;sheet.hide()

func _report() -> void:
	if not native_host or not is_instance_valid(sheet):return
	var status: Dictionary=game.snapshot()
	status.merge({"ready":is_ready,"density":density,"ambient":effects,"reduced":reduced,"night":night,"paused":paused,"menu":menu_id if sheet.visible else "",
		"frames":frames,"fps_limit":Engine.max_fps,"first_action_seconds":first_action_at,"engine":"4.7.2","version":"0.19.0",
		"viewport":[get_viewport_rect().size.x,get_viewport_rect().size.y],"menu_scroll":sheet_column.get_parent().scroll_vertical if is_instance_valid(sheet_column) else 0},true)
	native_host.report_status(JSON.stringify(status))

func _host_command(value: String) -> void:
	var request=JSON.parse_string(value)
	if not request is Dictionary:return
	if request.has("settings") and request.settings is Dictionary:_apply_settings(request.settings)
	if request.has("menu"):
		match request.menu:
			"activities":_activities()
			"miso":_miso_menu()
			"room":_room_menu()
			"close":sheet.hide()
	if request.has("paused"):paused=bool(request.paused)
	if request.has("action"):
		var target:=Vector2.INF
		if request.get("target",null) is Array and request.target.size()==2:
			target=Vector2(float(request.target[0]),float(request.target[1]))
		if target==Vector2.INF or (is_finite(target.x) and is_finite(target.y)):_command(request.action,target)
	_report()

func _review(args: PackedStringArray) -> void:
	game.mode="manual";game.reset_actor();first_action_at=-1;paused=true
	var output:="user://"
	for arg in args:
		if arg.begins_with("--review-dir="):output=arg.trim_prefix("--review-dir=")
	await get_tree().create_timer(.8).timeout
	for value in [128,96]:
		density=value;sprite.set_density(value)
		await get_tree().create_timer(.2).timeout
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png(output.path_join("room-%d.png"%value))
	density=128;sprite.set_density(128);_activities()
	await get_tree().create_timer(1.2).timeout
	assert(sheet.size.y<=521)
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png(output.path_join("activities.png"))
	sheet.hide()
	for action in ["eat","drink","eat_far","drink_far","scratch","perch_sleep","bookshelf_sleep","box","tunnel","window","groom","ball","feather"]:
		game.reset_actor();game.mode="manual";game.command(action)
		var captured:=false
		while game.busy:
			game.update(.025)
			var key: String=game.current.get("clip","")
			if not captured and game.progress>.4 and (key.begins_with("eat_") or key.begins_with("drink_") or key in ["scratch","groom","crawl","bat","pounce"]):
				captured=true
				_draw_actor();camera.position=_camera_focus()
				await RenderingServer.frame_post_draw
				get_viewport().get_texture().get_image().save_png(output.path_join(action+"-active.png"))
		_draw_actor();camera.position=_camera_focus()
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png(output.path_join(action+".png"))
	for facing in Game.DIRS:
		game.reset_actor();game.face=facing;_draw_actor();camera.position=_camera_focus()
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png(output.path_join("facing-"+facing+".png"))
	print("FOLIO_CAFE_REVIEW_COMPLETE")
	get_tree().quit()
