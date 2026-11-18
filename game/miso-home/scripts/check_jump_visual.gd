extends SceneTree

func _initialize() -> void:
	_review.call_deferred()

func _review() -> void:
	var scene=load("res://scenes/cafe.tscn").instantiate()
	root.add_child(scene);scene.paused=true
	while not scene.is_ready:await process_frame
	scene.game.mode="manual";scene.game.reset_actor()
	assert(scene.game.command("bookshelf_sleep"))
	var captured: Dictionary={}
	while scene.game.busy:
		scene.game.update(.025)
		if scene.game.current.get("type","")=="jump" and scene.game.progress>.48:
			var destination: String=scene.game.current.support_after
			if destination not in captured:
				captured[destination]=true
				scene._draw_actor();scene.camera.position=scene._camera_focus()
				await RenderingServer.frame_post_draw
				var output:="res://../../audit/v019/jump-"+destination+".png"
				assert(root.get_texture().get_image().save_png(output)==OK)
	assert(captured.size()==3)
	print("PASS: actual GPU mid-jump renders for floor to step, perch and bookshelf.")
	quit()
