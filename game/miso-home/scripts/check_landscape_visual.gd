extends SceneTree

func _initialize() -> void:
	_review.call_deferred()

func _review() -> void:
	var scene=load("res://scenes/cafe.tscn").instantiate()
	root.add_child(scene);scene.paused=true
	while not scene.is_ready:await process_frame
	scene.game.mode="manual";scene.game.reset_actor()
	for width in [2520,2280]:
		root.size=Vector2i(width,1080)
		await process_frame
		scene._layout_ui();scene._room_menu()
		await create_timer(1.5).timeout
		assert(scene.get_viewport_rect().size.x>scene.get_viewport_rect().size.y)
		assert(scene.sheet.size.y<=493)
		assert(scene.sheet.get_rect().end.x<scene.get_viewport_rect().size.x)
		await RenderingServer.frame_post_draw
		assert(root.get_texture().get_image().save_png("res://../../audit/v019/landscape-room-%d.png"%width)==OK)
	print("PASS: landscape room menus fit both 1080-high profiles.")
	quit()
