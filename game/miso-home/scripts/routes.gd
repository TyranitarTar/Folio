extends RefCounted
## Ground-space navigation; visual depth and pixel placement stay separate.
var grid := AStarGrid2D.new()
var obstacles: Array[Rect2] = []
const CELL := 0.125
const MARGIN := 0.18

func _init(environment: Dictionary) -> void:
	grid.region = Rect2i(0, 0, 41, 41)
	grid.cell_size = Vector2(CELL, CELL)
	grid.diagonal_mode = AStarGrid2D.DIAGONAL_MODE_ONLY_IF_NO_OBSTACLES
	grid.default_compute_heuristic = AStarGrid2D.HEURISTIC_EUCLIDEAN
	grid.default_estimate_heuristic = AStarGrid2D.HEURISTIC_EUCLIDEAN
	grid.update()
	for prop in environment.assets:
		if prop.solid and prop.surface == "floor" and prop.id not in ["bowls","ball_track"]:
			var center := Vector2(prop.pos[0], prop.pos[1])
			var size := Vector2(prop.footprint[0], prop.footprint[1])
			obstacles.append(Rect2(center - size / 2.0, size).grow(MARGIN))
	for y in range(41):
		for x in range(41):
			grid.set_point_solid(Vector2i(x, y), not clear(Vector2(x, y) * CELL))

func clear(point: Vector2) -> bool:
	if point.x < MARGIN or point.y < MARGIN or point.x > 5.0-MARGIN or point.y > 5.0-MARGIN:
		return false
	for rect in obstacles:
		if rect.has_point(point):
			return false
	return true

func segment(start: Vector2, end: Vector2) -> bool:
	if not clear(start) or not clear(end):
		return false
	var delta := end-start
	for rect in obstacles:
		var low := 0.0
		var high := 1.0
		for axis in range(2):
			if absf(delta[axis]) < 0.000001:
				if start[axis] < rect.position[axis] or start[axis] > rect.end[axis]:
					low = 2.0
					break
			else:
				var a: float = (rect.position[axis]-start[axis])/delta[axis]
				var b: float = (rect.end[axis]-start[axis])/delta[axis]
				low = maxf(low, minf(a,b))
				high = minf(high, maxf(a,b))
		if low <= high:
			return false
	return true

func path(start: Vector2, end: Vector2) -> PackedVector2Array:
	if not clear(end):
		return []
	if segment(start, end):
		return [end]
	var a := anchor(start)
	var b := anchor(end)
	if a.x < 0 or b.x < 0:
		return []
	var raw := grid.get_point_path(a,b)
	if raw.is_empty():
		return []
	raw.insert(0,start)
	raw.append(end)
	var smooth := PackedVector2Array()
	var index := 0
	while index < raw.size()-1:
		var next := index+1
		for candidate in range(index+2,raw.size()):
			if segment(raw[index],raw[candidate]):
				next = candidate
		if not segment(raw[index],raw[next]):
			return []
		smooth.append(raw[next])
		index = next
	return smooth

func anchor(point: Vector2) -> Vector2i:
	var base := Vector2i((point/CELL).round())
	var result := Vector2i(-1,-1)
	var distance := INF
	for y in range(-1,2):
		for x in range(-1,2):
			var cell := base+Vector2i(x,y)
			if not grid.is_in_boundsv(cell) or grid.is_point_solid(cell):
				continue
			var world := Vector2(cell)*CELL
			if segment(point,world) and point.distance_to(world)<distance:
				result=cell
				distance=point.distance_to(world)
	return result

func nearest(point: Vector2) -> Vector2:
	if clear(point):
		return point
	for radius in range(1,12):
		var best := Vector2.INF
		var distance := INF
		for y in range(-radius,radius+1):
			for x in range(-radius,radius+1):
				if absi(x)!=radius and absi(y)!=radius:
					continue
				var p := (point/CELL).round()*CELL+Vector2(x,y)*CELL
				if clear(p) and p.distance_to(point)<distance:
					best=p
					distance=p.distance_to(point)
		if best!=Vector2.INF:
			return best
	return Vector2.INF
