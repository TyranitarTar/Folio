extends SceneTree
const Game = preload("res://scripts/game.gd")

func _initialize() -> void:
	_run.call_deferred()

func finish(game: RefCounted) -> bool:
	var steps:=0
	while game.busy and steps<20000:
		game.update(.05)
		if game.busy and game.current.type=="walk" and game.occlusion!="tunnel":
			assert(game.world.clear(game.at),"Walk collision: "+game.action+" at "+str(game.at))
		steps+=1
	return not game.busy

func _run() -> void:
	var env: Dictionary=JSON.parse_string(FileAccess.get_file_as_string("res://assets/environment.json"))
	var game:=Game.new(env,472)
	game.mode="manual"
	for id in Game.ACTIONS:
		game.reset_actor()
		assert(game.command(id),"Unreachable action: "+id)
		assert(finish(game),"Action timed out: "+id)
		assert(game.counts.get(id,0)>0,"Action did not complete: "+id)
	var transitions:=0
	var jumps:=0
	for before in Game.ACTIONS:
		for after in Game.ACTIONS:
			var g:=Game.new(env,472)
			g.mode="manual"
			assert(g.command(before),before)
			assert(finish(g),before)
			assert(g.command(after),before+" -> "+after)
			assert(finish(g),before+" -> "+after)
			assert(g.counts.get(after,0)>0)
			transitions+=1
			jumps+=g.jumps_completed
	var queued:=Game.new(env,472)
	queued.mode="manual"
	assert(queued.command("bookshelf_sleep"))
	assert(queued.command("tunnel"))
	while queued.busy or not queued.pending.is_empty():
		queued.update(.05)
	assert(queued.counts.bookshelf_sleep==1 and queued.counts.tunnel==1)
	assert(queued.support=="floor" and queued.z==0)
	assert(queued.command("perch"))
	while queued.current.type!="jump":queued.update(.05)
	queued.command("stop")
	assert(finish(queued))
	assert(queued.support in ["step","perch"] and queued.z==queued.platforms[queued.support].z)
	assert(queued.command("down"));assert(finish(queued))
	assert(queued.support=="floor" and queued.z==0)
	var faces: Dictionary={}
	for i in range(72):
		var angle:=i*TAU/72.0
		var to:=Vector2(2,2.75)+Vector2(cos(angle),sin(angle))*.08
		faces[Game.heading(Vector2(2,2.75),to)]=true
	assert(faces.size()==8)
	print("PASS: 21 activities, ",transitions," ordered transitions, ",jumps," jumps, collision-free floor routes, safe queued actions/cancellation/descent, all eight facings.")
	quit(0)
