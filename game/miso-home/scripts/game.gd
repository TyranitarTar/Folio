extends RefCounted
## Deterministic room simulation. Drawing, native persistence and UI are adapters.
const Routes = preload("res://scripts/routes.gd")
const DIRS := {"sw":Vector2(0,1),"nw":Vector2(-1,0),"ne":Vector2(0,-1),"se":Vector2(1,0),"s":Vector2(1,1),"n":Vector2(-1,-1),"w":Vector2(-1,1),"e":Vector2(1,-1)}
const PACES := {"stroll":24.0,"walk":38.0,"trot":58.0,"run":78.0,"sprint":110.0}
const ACTIONS := {
	"eat":["Spis · forfra","bowls"],"drink":["Drik · forfra","bowls"],
	"eat_far":["Spis · bagfra","bowls"],"drink_far":["Drik · bagfra","bowls"],
	"scratch":["Hvæs kløer","cat_tower"],"step":["Nederste trin","cat_tower"],
	"perch":["Øverste platform","cat_tower"],"perch_watch":["Se dig omkring","cat_tower"],
	"perch_groom":["Pelspleje på platformen","cat_tower"],"perch_sleep":["Lur på platformen","cat_tower"],
	"bookshelf":["Hop til bogreolen","cubby_books"],"bookshelf_sleep":["Lur på bogreolen","cubby_books"],
	"box":["Gem dig i kassen","box"],"tunnel":["Kravl gennem tunnelen","tunnel"],
	"ball":["Leg med bolden","ball_track"],"feather":["Spring efter fjeren","feather"],
	"window":["Kig ud ad vinduet","window"],"door":["Undersøg døren","door"],
	"groom":["Pelspleje på tæppet","rug"],"sleep":["Lur på tæppet","rug"],"sofa":["Lur på sofaen","sofa"]}
const MODES := {"random":"Fri udforskning","manual":"Du bestemmer","morning":"En rolig morgen","playful":"Legestund","climbing":"Til vejrs og til ro","tour":"Alle 21 handlinger"}
const ROUTINES := {
	"morning":["groom","eat","drink","window","perch_watch","sofa"],
	"playful":["ball","feather","tunnel","box","scratch","drink","groom"],
	"climbing":["step","perch","perch_groom","perch_sleep","bookshelf","bookshelf_sleep","down","sleep"],
	"tour":["eat","drink","eat_far","drink_far","scratch","step","perch","perch_watch","perch_groom","perch_sleep","bookshelf","bookshelf_sleep","box","tunnel","ball","feather","window","door","groom","sleep","sofa"]}
var world: RefCounted
var props: Dictionary = {}
var platforms: Dictionary = {}
var approach := {"sofa":Vector2(1.625,1.5),"step":Vector2(1.5,4),"perch":Vector2(1.5,4),"box":Vector2(4,2.5),"window":Vector2(.75,1.875)}
var at := Vector2(2,2.75)
var z := 0.0
var face := "sw"
var support := "floor"
var resting := ""
var clip := "idle"
var effect := ""
var occlusion := ""
var progress := 0.0
var current: Dictionary = {}
var steps: Array[Dictionary] = []
var pending: Dictionary = {}
var action := ""
var pace := "walk"
var mode := "random"
var counts: Dictionary = {}
var revision := 0
var time := 0.0
var step_time := 0.0
var idle_time := 0.0
var next_activity := 1.0
var routine_index := 0
var last_action := ""
var stop_at_boundary := false
var rate := 1.0
var mark := Vector2.INF
var notice := "Godmorgen, Miso"
var jumps_completed := 0
var rng := RandomNumberGenerator.new()
var busy: bool:
	get: return not current.is_empty()

func _init(env: Dictionary, seed_value: int=0) -> void:
	world=Routes.new(env)
	if seed_value:
		rng.seed=seed_value
	else:
		rng.randomize()
	for prop in env.assets:
		props[prop.id]=prop
	platforms={"sofa":surface("sofa",Vector2(162,281)),"step":surface("cat_tower",Vector2(126,288)),
		"perch":surface("cat_tower",Vector2(171,211)),"bookshelf":surface("cubby_books",Vector2(162,282)),
		"box":surface("box",Vector2(162,312)),"window":surface("window",Vector2(175,328))}

static func project(p: Vector2, height: float=0) -> Vector2:
	return Vector2(512+(p.x-p.y)*64,308+(p.x+p.y)*32-height)

static func inverse(p: Vector2) -> Vector2:
	var v:=p-Vector2(512,308)
	return Vector2(v.x/128+v.y/64,v.y/64-v.x/128)

static func heading(a: Vector2,b: Vector2) -> String:
	var delta:=project(b)-project(a)
	var result:="sw"
	var score:=-INF
	for f in DIRS:
		var direction: Vector2 = project(DIRS[f])-project(Vector2.ZERO)
		var candidate:=delta.dot(direction.normalized())
		if candidate>score:
			score=candidate
			result=f
	return result

static func mirrored(f: String) -> bool:
	return f in ["se","ne","e"]

static func rear(f: String) -> bool:
	return f in ["nw","ne","n"]

func local(id: String,p: Vector2) -> Vector2:
	var prop: Dictionary=props[id]
	return project(Vector2(prop.pos[0],prop.pos[1]),prop.elevation)+p-Vector2(prop.anchor[0],prop.anchor[1])

func surface(id: String,p: Vector2) -> Dictionary:
	var prop: Dictionary=props[id]
	var dx: float=(p.x-prop.anchor[0])/128
	return {"at":Vector2(prop.pos[0]+dx,prop.pos[1]-dx),"z":prop.elevation+prop.anchor[1]-p.y}

func set_mode(value: String) -> void:
	if value not in MODES:
		return
	mode=value
	routine_index=0
	next_activity=0.7
	pending.clear()
	revision+=1

func command(id: String,target: Vector2=Vector2.INF) -> bool:
	if id=="stop":
		mode="manual"
		pending.clear()
		stop_at_boundary=busy
		return true
	if id=="home":
		reset_actor()
		mode="manual"
		return true
	if id not in ACTIONS and id not in ["move","down"]:
		return false
	if busy:
		pending={"id":id,"target":target}
		return true
	var plan:=Plan.new(self)
	var ok:=false
	if id=="move":
		var destination: Vector2=world.nearest(target)
		ok=destination!=Vector2.INF and plan.to_floor() and plan.walk(destination)
		if ok:
			mark=destination
	elif id=="down":
		ok=plan.to_floor()
	else:
		ok=plan.activity(id)
	if not ok:
		notice="Den vej er blokeret. Prøv et andet sted."
		return false
	if plan.steps.is_empty():
		return true
	steps=plan.steps
	action=id
	notice="Miso er på vej" if id not in ACTIONS else ACTIONS[id][0]
	_next()
	return true

func reset_actor() -> void:
	at=Vector2(2,2.75);z=0;face="sw";support="floor";resting="";clip="idle";effect="";occlusion=""
	current.clear();steps.clear();pending.clear();action="";mark=Vector2.INF;stop_at_boundary=false;idle_time=0
	revision+=1

func _next() -> void:
	step_time=0
	if steps.is_empty():
		current={}
		if action in ACTIONS:
			counts[action]=mini(100000,int(counts.get(action,0))+1)
			revision+=1
			last_action=action
		action=""
		effect=""
		occlusion="box" if support=="box" else ""
		clip="sleep" if resting=="sleep" else "sit" if support=="box" else "idle"
		mark=Vector2.INF
		idle_time=0
		next_activity=9.0 if resting=="sleep" else rng.randf_range(2.0,4.0)
		return
	current=steps.pop_front()
	clip=current.clip
	face=current.face
	effect=current.get("effect","")
	occlusion=current.get("occlusion","")
	rate=current.get("rate",1.0)
	progress=0

func update(delta: float) -> void:
	var remaining:=maxf(0,delta)
	time+=remaining
	while busy and remaining>0:
		var dt:=minf(remaining,current.duration-step_time)
		step_time+=dt
		remaining-=dt
		progress=clampf(step_time/current.duration,0,1)
		if current.has("from"):
			var t:=progress
			if current.type=="jump":
				t=clampf((progress-.18)/.64,0,1)
				z=lerpf(current.z0,current.z1,t)+4*current.apex*t*(1-t)
			else:
				z=current.get("z0",0.0)
			at=current.from.lerp(current.to,t)
		elif current.has("hop"):
			z=float(platforms.get(support,{"z":0}).z)+sin(progress*PI)*current.hop
		if step_time+0.000001>=current.duration:
			if current.has("to"):
				at=current.to
			support=current.get("support_after",support)
			z=current.get("z1",float(platforms.get(support,{"z":0}).z))
			resting=current.get("resting_after",resting)
			if current.type=="jump":
				jumps_completed+=1
			if stop_at_boundary:
				steps.clear();action="";stop_at_boundary=false
			_next()
	if not busy:
		idle_time+=remaining
		if not pending.is_empty():
			var queued:=pending.duplicate()
			pending.clear()
			command(queued.id,queued.target)
		elif mode!="manual":
			next_activity-=remaining
			if next_activity<=0:
				var id: String
				if mode=="random":
					var choices:=ACTIONS.keys().filter(func(k):return k!=last_action)
					id=choices[rng.randi_range(0,choices.size()-1)]
				else:
					var routine: Array=ROUTINES.get(mode,ROUTINES.tour)
					id=routine[routine_index%routine.size()]
					routine_index+=1
				command(id)
				next_activity=2.0

func snapshot() -> Dictionary:
	return {"at":[at.x,at.y],"z":z,"face":face,"support":support,"resting":resting,"clip":clip,
		"action":action,"busy":busy,"pending":pending.get("id",""),"progress":progress,"mode":mode,
		"pace":pace,"counts":counts.duplicate(),"notice":notice,"time":time,"revision":revision,
		"step_type":current.get("type",""),"jumps":jumps_completed}

class Plan:
	extends RefCounted
	var game: RefCounted
	var at: Vector2
	var z: float
	var face: String
	var support: String
	var resting: String
	var steps: Array[Dictionary]=[]
	func _init(owner: RefCounted) -> void:
		game=owner;at=owner.at;z=owner.z;face=owner.face;support=owner.support;resting=owner.resting
	func turn(to: String) -> void:
		if face!=to:
			steps.append({"type":"pose","clip":"idle","face":to,"duration":.12})
			face=to
	func walk(to: Vector2) -> bool:
		var path: PackedVector2Array=game.world.path(at,to)
		if path.is_empty():
			return at.distance_to(to)<.001
		var speed: float=PACES[game.pace]*.75
		for point in path:
			if at.distance_to(point)<.001:
				continue
			var direction: String=game.heading(at,point)
			turn(direction)
			steps.append({"type":"walk","clip":"walk","face":direction,"duration":maxf(.06,game.project(at).distance_to(game.project(point))/speed),
				"from":at,"to":point,"z0":0.0,"z1":0.0,"support_after":"floor","rate":PACES[game.pace]/PACES.walk})
			at=point
		return true
	func pose(name: String,duration: float=2.4,facing: String="sw",extra: Dictionary={}) -> void:
		turn(facing)
		var step: Dictionary={"type":"pose","clip":name,"face":facing,"duration":duration}
		step.merge(extra,true)
		steps.append(step)
	func jump(target: Dictionary,destination: String,extra: Dictionary={}) -> void:
		var f: String="se" if target.at.x>at.x else "nw"
		if absf(target.at.y-at.y)>absf(target.at.x-at.x):
			f="sw" if target.at.y>at.y else "ne"
		turn(f)
		var step: Dictionary={"type":"jump","clip":"jump_nw" if game.rear(f) else "jump_sw","face":f,"duration":1.1,
			"from":at,"to":target.at,"z0":z,"z1":target.z,"apex":maxf(32,absf(target.z-z)*.6+20),"support_after":destination}
		step.merge(extra,true)
		steps.append(step)
		at=target.at;z=target.z;support=destination
	func wake() -> void:
		if resting!="":
			pose("curl_up",1.4,"sw",{"resting_after":""})
			resting=""
	func settle() -> void:
		pose("curl_down",1.5,"sw",{"resting_after":"sleep"})
		resting="sleep"
	func to_floor() -> bool:
		wake()
		if support=="floor":
			return true
		if support=="box":
			pose("sit",.5,"sw",{"occlusion":"box"})
		if support=="bookshelf":
			jump(game.platforms.perch,"perch")
		if support=="perch":
			jump(game.platforms.step,"step")
		if support=="step":
			jump({"at":game.approach.step,"z":0.0},"floor")
			return true
		if support not in game.approach:
			return false
		jump({"at":game.approach[support],"z":0.0},"floor",{"occlusion":"box"} if support=="box" else {})
		return true
	func platform(name: String) -> bool:
		wake()
		if support==name:
			return true
		if name=="bookshelf":
			if not platform("perch"):return false
			jump(game.platforms.bookshelf,"bookshelf")
			return true
		if name=="perch":
			if support=="bookshelf":
				jump(game.platforms.perch,"perch")
				return true
			if not platform("step"):return false
			jump(game.platforms.perch,"perch")
			return true
		if name=="step":
			if support=="bookshelf":jump(game.platforms.perch,"perch")
			if support=="perch":
				jump(game.platforms.step,"step")
				return true
		if not to_floor() or not walk(game.approach[name]):return false
		jump(game.platforms[name],name,{"occlusion":"box"} if name=="box" else {})
		return true
	func activity(id: String) -> bool:
		if id in ["perch_sleep","bookshelf_sleep","perch_groom","perch_watch"]:
			if not platform("bookshelf" if id=="bookshelf_sleep" else "perch"):return false
			if id.ends_with("sleep"):settle()
			else:pose("groom" if id=="perch_groom" else "idle",4.0)
			return true
		if id in ["sofa","step","perch","bookshelf","box","window"]:
			if not platform(id):return false
			if id=="sofa":settle()
			else:pose("sit" if id=="box" else "idle",1.8,face,{"occlusion":"box"} if id=="box" else {})
			return true
		if not to_floor():return false
		if id in ["eat","drink","eat_far","drink_far"]:
			var far: bool=id.ends_with("_far")
			var kind: String="eat" if id.begins_with("eat") else "drink"
			var facing: String="sw" if far else "ne"
			var contact: Vector2=game.local("bowls",Vector2(153,292) if kind=="eat" else Vector2(175,299))
			var mouth:=Vector2(27,105) if far else Vector2(28,98)
			var offset: Vector2=(mouth-Vector2(64,112))*.75
			if game.mirrored(facing):offset.x=-offset.x
			if not walk(game.inverse(contact-offset)):return false
			pose(kind+"_sw" if far else kind+"_nw",4.0,facing,{"effect":"feeding"})
			return true
		if id=="scratch":
			var p: Vector2=game.local("cat_tower",Vector2(170,267))
			if not walk(game.inverse(p+Vector2(26,38))-Vector2(.125,.125)):return false
			pose("scratch",5.2,"nw",{"effect":"scratch"})
			return true
		if id=="tunnel":
			if not walk(Vector2(1.5,4.5)):return false
			pose("tunnel_enter",.9,"se")
			steps.append({"type":"walk","clip":"crawl","face":"se","duration":game.project(Vector2(1.5,4.5)).distance_to(game.project(Vector2(4,4.5)))/24.0,
				"from":at,"to":Vector2(4,4.5),"z0":0.0,"z1":0.0,"support_after":"floor","occlusion":"tunnel","rate":1.0})
			at=Vector2(4,4.5)
			pose("tunnel_exit",.9,"se")
			return true
		if id in ["ball","feather"]:
			var p: Vector2=game.local("ball_track",Vector2(181,288)) if id=="ball" else game.local("feather",Vector2(167,306))
			if not walk(game.inverse(p+Vector2(20,10))):return false
			pose("bat" if id=="ball" else "pounce",3.0,"sw",{"effect":id,"hop":0.0 if id=="ball" else 12.0})
			return true
		if id in ["groom","sleep"]:
			if not walk(Vector2(2.1,2.5)):return false
			if id=="sleep":settle()
			else:pose("groom",5.2)
			return true
		if id=="door":
			if not walk(Vector2(4.25,.5)):return false
			pose("sniff",3.2,"se")
			return true
		return false
