extends SceneTree

func _initialize() -> void:
	var output:="res://COPYRIGHT-GODOT.txt"
	for arg in OS.get_cmdline_user_args():
		if arg.begins_with("--notices="):output=arg.trim_prefix("--notices=")
	var body:="Godot Engine 4.7.2 · MIT license and bundled third-party notices\n\n"+Engine.get_license_text()+"\n\n"
	for component in Engine.get_copyright_info():
		body+=str(component.name)+"\n"
		for part in component.parts:
			body+="Files: "+", ".join(part.files)+"\nCopyright: "+"; ".join(part.copyright)+"\nLicense: "+part.license+"\n\n"
	var licenses:=Engine.get_license_info()
	for name in licenses:body+=str(name)+"\n\n"+str(licenses[name])+"\n\n"
	var file:=FileAccess.open(output,FileAccess.WRITE)
	assert(file!=null)
	file.store_string(body);file.close()
	print("PASS: engine MIT and ",licenses.size()," bundled license texts preserved.")
	quit()
