extends Sprite2D
const Game=preload("res://scripts/game.gd")
var library: Dictionary
var textures: Dictionary={}
var density:=128
var active_clip:=""
var active_frame:=0

func _init() -> void:
	library=JSON.parse_string(FileAccess.get_file_as_string("res://assets/animation-library.json"))
	centered=false
	texture_filter=CanvasItem.TEXTURE_FILTER_NEAREST
	set_density(128)

func set_density(value: int) -> void:
	density=96 if value==96 else 128
	for name in library.clips:
		var loaded: Array[Texture2D]=[]
		for file in library.clips[name].files:
			loaded.append(load(file.replace("{density}",str(density))))
		textures[name]=loaded
	scale=Vector2.ONE*96.0/density
	offset=Vector2(-density*.5,-density*.875)

func draw_game(game: RefCounted) -> void:
	var name: String=game.clip
	var canonical: String=library.facingClips[game.face][0]
	if name in ["idle","walk"]:
		name+="_"+canonical
	if name not in library.clips:
		name="idle_sw"
	var definition: Dictionary=library.clips[name]
	var clock: float=game.step_time*game.rate if game.busy else game.idle_time
	var total:=0.0
	for duration in definition.durations:
		total+=duration
	var t:=fmod(clock,total) if definition.loop else minf(clock,total-.00001)
	var frame:=0
	while frame<definition.durations.size()-1 and t>=definition.durations[frame]:
		t-=definition.durations[frame]
		frame+=1
	if game.busy and game.current.type=="jump":
		var p: float=game.progress
		frame=0 if p<.18 or p>.86 else 1 if p<.35 or p>.75 else 2
	texture=textures[name][frame]
	var root: Array=definition.roots[frame]
	offset=-Vector2(root[0],root[1])*density/128.0
	flip_h=Game.mirrored(game.face)
	position=Vector2(0,-game.z)
	active_clip=name
	active_frame=frame
