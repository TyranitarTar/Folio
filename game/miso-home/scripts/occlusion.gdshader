shader_type canvas_item;
render_mode unshaded;
uniform sampler2D occluders : filter_nearest, repeat_disable;
uniform sampler2D spans_map : filter_nearest, repeat_disable;
uniform vec4 props[7]; // logical top-left x/y, maximum height, unused
uniform float actor_depth=0.0;
uniform float actor_height=0.0;
uniform bool inside_tunnel=false;
uniform bool inside_box=false;
uniform bool on_perch=false;
uniform bool on_step=false;
uniform bool on_sofa=false;
uniform bool scratching=false;
varying vec2 world_position;

void vertex() {
	world_position=(MODEL_MATRIX*vec4(VERTEX,0.0,1.0)).xy;
}

bool box_front(vec2 p) {
	vec2 points[6]=vec2[6](vec2(101,279),vec2(159,304),vec2(213,281),vec2(208,312),vec2(160,335),vec2(109,312));
	bool inside=false;
	for(int i=0,j=5;i<6;j=i++) {
		vec2 a=points[i];vec2 b=points[j];
		if((a.y>p.y)!=(b.y>p.y) && p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x) inside=!inside;
	}
	return inside;
}
bool sofa_arm(vec2 p) {
	vec2 points[5]=vec2[5](vec2(208,252),vec2(248,273),vec2(248,315),vec2(215,338),vec2(208,283));
	bool inside=false;
	for(int i=0,j=4;i<5;j=i++) {
		vec2 a=points[i];vec2 b=points[j];
		if((a.y>p.y)!=(b.y>p.y) && p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x) inside=!inside;
	}
	return inside;
}
void fragment() {
	float cover=0.0;
	for(int i=0;i<7;i++) {
		vec2 p=world_position-props[i].xy;
		if(p.x<0.0 || p.y<0.0 || p.x>=320.0 || p.y>=384.0) continue;
		float pa=texture(occluders,(vec2(float(i%4)*640.0,float(i/4)*768.0)+p*2.0)/vec2(2560,1536)).a;
		bool hole=dot((p-vec2(184,322))/vec2(17,23),(p-vec2(184,322))/vec2(17,23))<1.0;
		if(i==6 && inside_tunnel && p.x>=111.0 && p.x<=194.0 && !hole) cover=1.0;
		if(pa<=0.0) continue;
		bool hide=false;
		if(i==6 && inside_tunnel) hide=!hole;
		else if(i==5 && inside_box) hide=actor_height<53.0 && box_front(p);
		else {
			if(i==3) {
				if(scratching && p.x>=155.0 && p.x<=185.0 && p.y>=220.0 && p.y<305.0) continue;
				if(actor_height>=107.0 && on_perch) continue;
				if(actor_height>=29.0 && on_step && p.x<165.0 && p.y>=270.0 && p.y<309.0) continue;
			}
			if(i==2 && actor_height>=35.0 && on_sofa && !sofa_arm(p)) continue;
			vec4 span=texture(spans_map,vec2((floor(p.x)+0.5)/320.0,(float(i)+0.5)/7.0));
			if(span.a<0.5) continue;
			float h=props[i].z;
			if(i==3) {
				if(p.x<165.0 && p.y>=270.0 && p.y<309.0) h=34.0;
				else if(p.y>=309.0 && !(p.x>=160.0 && p.x<=181.0 && p.y<313.0)) h=12.0;
			}
			if(actor_height>h+3.0) continue;
			float near_y=max(props[i].y+span.r,world_position.y);
			float far_y=min(props[i].y+span.g,world_position.y+h);
			hide=far_y>=near_y && far_y>actor_depth+1.0;
		}
		if(hide) cover=1.0-(1.0-cover)*(1.0-pa);
	}
	COLOR.a*=1.0-cover;
}
