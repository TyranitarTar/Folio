extends RefCounted
var material:=ShaderMaterial.new()
var parameters:=PackedVector4Array()

func _init() -> void:
	var entries: Array=JSON.parse_string(FileAccess.get_file_as_string("res://assets/occluders.json"))
	var image:=Image.create(320,entries.size(),false,Image.FORMAT_RGBAF)
	for index in range(entries.size()):
		var entry: Dictionary=entries[index]
		parameters.append(Vector4(entry.topLeft[0],entry.topLeft[1],entry.height,0))
		for x in range(320):
			if entry.spans[x]!=null:
				image.set_pixel(x,index,Color(entry.spans[x][0],entry.spans[x][1],0,1))
	material.shader=load("res://scripts/occlusion.gdshader")
	material.set_shader_parameter("occluders",load("res://assets/occluders.png"))
	material.set_shader_parameter("spans_map",ImageTexture.create_from_image(image))
	material.set_shader_parameter("props",parameters)

func update(game: RefCounted) -> void:
	var target: String=game.current.get("support_after","")
	material.set_shader_parameter("actor_depth",game.project(game.at).y)
	material.set_shader_parameter("actor_height",game.z)
	material.set_shader_parameter("inside_tunnel",game.occlusion=="tunnel")
	material.set_shader_parameter("inside_box",game.support=="box" or target=="box" or game.occlusion=="box")
	material.set_shader_parameter("on_perch",game.support=="perch" or target in ["perch","bookshelf"])
	material.set_shader_parameter("on_step",game.support=="step" or target=="step")
	material.set_shader_parameter("on_sofa",game.support=="sofa" or target=="sofa")
	material.set_shader_parameter("scratching",game.effect=="scratch")
