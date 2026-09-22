package interaction

import (
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"strconv"
	"strings"
)

const (
	GameTypeSingleChoice = "SINGLE_CHOICE"
	GameTypeTrueFalse    = "TRUE_FALSE"
	GameTypeMatching     = "MATCHING"
	GameTypeRunner       = "RUNNER"
)

const runnerDinosaurCSS = `.runner-player{width:92px;height:76px;left:64px;bottom:18px}.runner-dino-tail{bottom:25px;left:0;width:48px;height:28px;background:#6757e8;clip-path:polygon(100% 34%,76% 34%,62% 27%,47% 27%,34% 18%,20% 18%,0 0,0 22%,15% 35%,29% 35%,43% 48%,58% 48%,72% 63%,100% 63%);filter:drop-shadow(0 2px 0 #4c3fc0)}.runner-dino-tail:before{position:absolute;right:5px;bottom:5px;width:15px;height:7px;background:#8d83f2;content:"";opacity:.75}.runner-dino-body{bottom:12px;left:18px;width:49px;height:34px;background:#7568eb;clip-path:polygon(0 15%,9% 15%,9% 0,78% 0,78% 10%,100% 10%,100% 84%,85% 100%,16% 100%,16% 86%,0 86%);box-shadow:inset -6px -6px 0 rgba(58,43,171,.24),inset 6px 5px 0 rgba(161,153,255,.18)}.runner-dino-body:before{position:absolute;right:5px;bottom:5px;width:28px;height:10px;background:#978ef6;clip-path:polygon(0 30%,100% 0,100% 100%,15% 100%);content:"";opacity:.72}.runner-dino-body:after{position:absolute;top:-6px;left:8px;width:30px;height:9px;background:#4c3fc0;clip-path:polygon(0 100%,15% 24%,30% 100%,48% 0,64% 100%,82% 28%,100% 100%);content:""}.runner-dino-neck{bottom:35px;left:53px;width:18px;height:32px;background:#7568eb;clip-path:polygon(13% 100%,13% 17%,29% 0,100% 0,100% 100%);box-shadow:inset -4px 0 0 rgba(58,43,171,.18)}.runner-dino-neck:before{position:absolute;top:3px;left:0;width:9px;height:19px;background:#978ef6;clip-path:polygon(0 0,100% 0,100% 100%,35% 76%);content:"";opacity:.55}.runner-dino-head{right:0;bottom:57px;width:39px;height:25px;background:#7d70ef;clip-path:polygon(0 17%,13% 0,78% 0,78% 9%,100% 9%,100% 64%,88% 64%,88% 82%,30% 82%,30% 100%,0 100%);box-shadow:inset -5px -4px 0 rgba(58,43,171,.2)}.runner-dino-head:before{position:absolute;top:6px;right:9px;width:6px;height:6px;background:#fff;box-shadow:inset 2px 0 0 #172033;content:""}.runner-dino-head:after{position:absolute;right:0;bottom:4px;width:20px;height:3px;background:#4c3fc0;content:""}.runner-dino-arm{right:7px;bottom:34px;width:16px;height:15px;background:#6757e8;clip-path:polygon(0 0,55% 8%,100% 72%,78% 100%,44% 45%,0 38%);transform:rotate(7deg)}.runner-dino-leg{bottom:0;width:11px;height:24px;background:#4c3fc0;clip-path:polygon(8% 0,100% 0,83% 67%,100% 82%,100% 100%,15% 100%,0 82%,23% 69%);box-shadow:none}.runner-dino-leg:after{position:absolute;right:-5px;bottom:0;width:15px;height:4px;background:#30258e;content:""}.runner-dino-leg--one{left:27px}.runner-dino-leg--two{left:51px}.runner-player.is-running .runner-dino-leg--one{animation:dino-leg-one .22s linear infinite alternate}.runner-player.is-running .runner-dino-leg--two{animation:dino-leg-two .22s linear infinite alternate}`

//go:embed runner-dinosaur-reference-v1.png
var runnerDinosaurPNG []byte

var runnerDinosaurDataURI = "data:image/png;base64," + base64.StdEncoding.EncodeToString(runnerDinosaurPNG)

const runnerDinosaurSpriteCSS = `.runner-player{width:92px;height:76px;left:64px;bottom:18px;overflow:visible;background:transparent;box-shadow:none;clip-path:none}.runner-dino-sprite{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;image-rendering:pixelated;filter:drop-shadow(0 2px 0 rgba(47,38,126,.28))}`

const runnerDuoCSS = `.runner-arena{height:342px;background:linear-gradient(180deg,#c9e8ff 0%,#f1f9ff 100%)}.runner-lane{position:absolute;z-index:1;right:0;left:0;height:50%;overflow:hidden}.runner-lane--a{top:0;border-bottom:1px solid rgba(117,139,174,.22)}.runner-lane--b{bottom:0}.runner-lane__name{position:absolute;z-index:4;top:10px;left:14px;padding:4px 9px;border:1px solid rgba(255,255,255,.75);border-radius:999px;background:rgba(255,255,255,.68);color:#42577d;font-size:12px;font-weight:800}.runner-lane__ground{position:absolute;right:0;bottom:0;left:0;height:18px;background:linear-gradient(180deg,#78aa62 0,#91c176 22%,#b8d68e 22%,#a9c77b 100%);box-shadow:0 -2px 0 rgba(73,125,70,.24)}.runner-lane__ground:before{position:absolute;top:-3px;right:0;left:0;height:5px;background:repeating-linear-gradient(90deg,#6d9f5b 0 13px,#86b96c 13px 25px,#74a862 25px 39px);content:""}.runner-lane__ground:after{position:absolute;right:0;bottom:4px;left:0;height:3px;background:repeating-linear-gradient(90deg,rgba(111,145,82,.35) 0 4px,transparent 4px 23px);content:""}.runner-lane .runner-player{bottom:18px;left:72px}.runner-lane .runner-obstacle{bottom:18px}.runner-player--b{filter:hue-rotate(112deg) saturate(.82)}.runner-player.is-running{animation:runner-bob .22s linear infinite alternate}.runner-player.is-jumping{filter:drop-shadow(0 5px 0 rgba(47,38,126,.16))}.runner-status{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.runner-status__item{padding:5px 9px;border-radius:999px;background:#f3f6ff;color:#52617d;font-size:12px;font-weight:700}.runner-status__item--a{border:1px solid #b8c8ff}.runner-status__item--b{border:1px solid #b9dfc5}.runner-status__item strong{color:#172033}.runner-controls--duo{display:grid;grid-template-columns:1fr 1fr auto;gap:9px}.runner-controls--duo button{min-width:0}.runner-controls--duo small{grid-column:1/-1;margin-left:0}.runner-question__tag--a{color:#4f65c8}.runner-question__tag--b{color:#328452}.runner-question--a{border-color:#b8c8ff;background:#f8f9ff}.runner-question--b{border-color:#b9dfc5;background:#f7fff9}`

const dualRunnerTemplate = `function renderRunner(){const qList=spec.questions||[];root.innerHTML='<section class="card runner-card"><div class="runner-top"><div><div class="runner-kicker">材料双人闯关</div><h2 class="runner-title">'+esc(spec.title)+'</h2><p>两名学生一起前进；谁撞到障碍，谁回答一个材料问题。</p></div><div class="runner-score" id="runner-score">0 米</div></div><div class="runner-hud"><span id="runner-stage">准备出发</span><span class="runner-status"><span class="runner-status__item runner-status__item--a"><strong>学生甲</strong> <b id="runner-hearts-a">♥♥♥</b></span><span class="runner-status__item runner-status__item--b"><strong>学生乙</strong> <b id="runner-hearts-b">♥♥♥</b></span><span id="runner-progress">检查点 1 / '+qList.length+'</span></span></div><div class="runner-arena" id="runner-arena" tabindex="0" aria-label="双人小恐龙闯关区域"><div class="runner-cloud runner-cloud--one"></div><div class="runner-cloud runner-cloud--two"></div><div class="runner-sun"></div><div class="runner-mountain runner-mountain--one"></div><div class="runner-mountain runner-mountain--two"></div><div class="runner-lane runner-lane--a" id="runner-lane-a"><div class="runner-lane__name">学生甲 · W</div><div class="runner-player runner-player--a" id="runner-player-a">__DINO__</div><div class="runner-lane__ground"></div><div id="runner-obstacles-a"></div></div><div class="runner-lane runner-lane--b" id="runner-lane-b"><div class="runner-lane__name">学生乙 · ↑</div><div class="runner-player runner-player--b" id="runner-player-b">__DINO__</div><div class="runner-lane__ground"></div><div id="runner-obstacles-b"></div></div><div class="runner-overlay" id="runner-overlay"><div><strong>准备开始</strong><span>学生甲按 W，学生乙按 ↑；谁撞到障碍，谁答题。</span><button id="runner-start">开始双人闯关</button></div></div></div><div class="runner-controls runner-controls--duo"><button id="runner-jump-a">学生甲跳跃（W）</button><button id="runner-jump-b">学生乙跳跃（↑）</button><button id="runner-restart" class="secondary">重新开始</button><small>同一键盘双人操作：甲 W，乙 ↑；答题时两条赛道都会暂停。</small></div><div class="runner-question" id="runner-question" hidden></div></section>';const arena=document.getElementById('runner-arena'),overlay=document.getElementById('runner-overlay'),questionBox=document.getElementById('runner-question'),stage=document.getElementById('runner-stage'),scoreBox=document.getElementById('runner-score'),progressBox=document.getElementById('runner-progress'),heartsA=document.getElementById('runner-hearts-a'),heartsB=document.getElementById('runner-hearts-b');const players={a:{name:'学生甲',el:document.getElementById('runner-player-a'),layer:document.getElementById('runner-obstacles-a'),y:0,v:0,hearts:3,obstacles:[],nextSpawn:950},b:{name:'学生乙',el:document.getElementById('runner-player-b'),layer:document.getElementById('runner-obstacles-b'),y:0,v:0,hearts:3,obstacles:[],nextSpawn:1350}};let running=false,asking=false,finished=false,raf=0,last=0,distance=0,checkpoint=0,activePlayer='a';const playerX=72,gravity=1700,jumpPower=655;function update(){scoreBox.textContent=Math.floor(distance)+' 米';heartsA.textContent='♥'.repeat(Math.max(0,players.a.hearts))+'♡'.repeat(Math.max(0,3-players.a.hearts));heartsB.textContent='♥'.repeat(Math.max(0,players.b.hearts))+'♡'.repeat(Math.max(0,3-players.b.hearts));progressBox.textContent='检查点 '+Math.min(checkpoint+1,qList.length)+' / '+qList.length;players.a.el.style.transform='translateY('+(-players.a.y)+'px)';players.b.el.style.transform='translateY('+(-players.b.y)+'px)'}function jump(who){const p=players[who];if(!running||asking||finished||p.y>1)return;p.v=jumpPower;p.el.classList.add('is-jumping');setTimeout(()=>p.el.classList.remove('is-jumping'),220)}function spawn(who){const p=players[who],node=document.createElement('div'),wide=Math.random()<.28;node.className='runner-obstacle'+(wide?' runner-obstacle--wide':'');const obstacle={node,x:arena.clientWidth+30,width:wide?42:24,height:48+Math.random()*20};node.style.height=obstacle.height+'px';p.layer.appendChild(node);p.obstacles.push(obstacle)}function hit(p,o){return o.x<playerX+58&&o.x+o.width>playerX+5&&p.y<o.height-7}function begin(){if(finished){location.reload();return}if(asking)return;running=true;players.a.el.classList.add('is-running');players.b.el.classList.add('is-running');overlay.hidden=true;stage.textContent='两人奔跑中';last=performance.now();arena.focus();cancelAnimationFrame(raf);raf=requestAnimationFrame(tick)}function finish(win){running=false;asking=false;finished=true;players.a.el.classList.remove('is-running');players.b.el.classList.remove('is-running');cancelAnimationFrame(raf);questionBox.hidden=true;overlay.hidden=false;overlay.innerHTML='<div><strong>'+ (win?'双人闯关成功！':'闯关结束') +'</strong><span>'+ (win?'两名学生完成了全部材料检查点。':'有一名学生生命值耗尽，需要复习材料后再来挑战。') +'</span><button id="runner-overlay-restart">'+(win?'再来一次':'重新开始')+'</button></div>';document.getElementById('runner-overlay-restart').onclick=()=>location.reload();stage.textContent=win?'已完成':'需要复习';update()}function showQuestion(who){running=false;asking=true;activePlayer=who;players.a.el.classList.remove('is-running');players.b.el.classList.remove('is-running');cancelAnimationFrame(raf);const q=qList[checkpoint];if(!q){finish(true);return}const p=players[who];stage.textContent=p.name+'答题中';questionBox.hidden=false;questionBox.className='runner-question runner-question--'+who;questionBox.innerHTML='<div class="runner-question__tag runner-question__tag--'+who+'">'+esc(p.name)+'撞到障碍 · 材料检查点 '+(checkpoint+1)+'</div><h3>'+esc(q.prompt)+'</h3><div class="choices">'+(q.options||[]).map(o=>'<button class="choice" data-id="'+esc(o.id)+'">'+esc(o.text)+'</button>').join('')+'</div><div class="runner-feedback" id="runner-feedback"></div><div class="source">材料来源：文件 '+esc(q.source.fileId)+' · '+esc(q.source.locator)+'</div>';questionBox.querySelectorAll('.choice').forEach(button=>button.onclick=()=>answer(button.dataset.id,q,questionBox));questionBox.scrollIntoView({behavior:'smooth',block:'nearest'})}function answer(id,q,box){const buttons=[...box.querySelectorAll('.choice')];buttons.forEach(button=>button.disabled=true);const p=players[activePlayer],ok=(q.answer||[]).includes(id),feedback=box.querySelector('#runner-feedback');if(ok){checkpoint++;distance+=100;feedback.innerHTML='<div class="feedback">'+esc(p.name)+'回答正确，可以继续前进。 '+esc(q.explanation)+'</div><div class="actions"><button id="runner-continue">继续双人闯关</button></div>';document.getElementById('runner-continue').onclick=()=>{if(checkpoint>=qList.length){finish(true);return}questionBox.hidden=true;asking=false;questionBox.className='runner-question';players.a.nextSpawn=700;players.b.nextSpawn=920;begin()}}else{p.hearts--;feedback.innerHTML='<div class="feedback">'+esc(p.name)+'回答不正确，请依据材料再试一次。 '+esc(q.explanation)+'</div>'+(p.hearts>0?'<div class="actions"><button id="runner-retry">'+esc(p.name)+'重新作答</button></div>':'');if(p.hearts>0){document.getElementById('runner-retry').onclick=()=>{buttons.forEach(button=>{button.disabled=false;button.classList.remove('wrong')});feedback.innerHTML=''}}else{finish(false)}}update()}function tick(now){if(!running||asking||finished)return;const dt=Math.min((now-last)/1000,.04);last=now;const speed=260+Math.min(150,distance*.12);distance+=speed*dt*.06;for(const who of ['a','b']){const p=players[who];p.v-=gravity*dt;p.y=Math.max(0,p.y+p.v*dt);if(p.y===0)p.v=0;p.nextSpawn-=dt*1000;if(p.nextSpawn<=0){spawn(who);p.nextSpawn=1050+Math.random()*850}for(let i=p.obstacles.length-1;i>=0;i--){const obstacle=p.obstacles[i];obstacle.x-=speed*dt;obstacle.node.style.transform='translateX('+obstacle.x+'px)';if(hit(p,obstacle)){obstacle.node.remove();p.obstacles.splice(i,1);showQuestion(who);return}if(obstacle.x+obstacle.width<0){obstacle.node.remove();p.obstacles.splice(i,1)}}}update();raf=requestAnimationFrame(tick)}document.getElementById('runner-start').onclick=begin;document.getElementById('runner-jump-a').onclick=()=>jump('a');document.getElementById('runner-jump-b').onclick=()=>jump('b');document.getElementById('runner-restart').onclick=()=>location.reload();arena.addEventListener('keydown',event=>{if(event.code==='KeyW'){event.preventDefault();if(!running&&!asking)begin();else jump('a')}else if(event.code==='ArrowUp'){event.preventDefault();if(!running&&!asking)begin();else jump('b')}});arena.addEventListener('click',event=>{if(event.target===arena&&!running&&!asking)begin()});update()}`

type SourceRef struct {
	FileID  int64  `json:"fileId"`
	Locator string `json:"locator"`
	Claim   string `json:"claim,omitempty"`
}

type Option struct {
	ID   string `json:"id"`
	Text string `json:"text"`
}

type MatchPair struct {
	ID     string    `json:"id"`
	Left   string    `json:"left"`
	Right  string    `json:"right"`
	Source SourceRef `json:"source"`
}

type Question struct {
	ID          string      `json:"id"`
	Type        string      `json:"type"`
	Prompt      string      `json:"prompt"`
	Options     []Option    `json:"options,omitempty"`
	Answer      []string    `json:"answer,omitempty"`
	Explanation string      `json:"explanation"`
	Source      SourceRef   `json:"source"`
	Pairs       []MatchPair `json:"pairs,omitempty"`
}

type Spec struct {
	Title                      string      `json:"title"`
	Description                string      `json:"description"`
	GameType                   string      `json:"gameType"`
	SourceSpecificationID      string      `json:"sourceSpecificationId"`
	SourceSpecificationVersion int         `json:"sourceSpecificationVersion"`
	SourceRefs                 []SourceRef `json:"sourceRefs"`
	Questions                  []Question  `json:"questions"`
}

// BuildFromLockedSpecification creates a bounded GameSpec from the immutable
// semantic plan. It deliberately does not ask a model to emit HTML or invent
// facts: every prompt, option and pair is copied from a slide point that has a
// material/teacher source reference.
func BuildFromLockedSpecification(raw any, specificationID string, version int, materialFileIDs ...int64) (Spec, error) {
	return BuildFromLockedSpecificationWithType(raw, specificationID, version, "", materialFileIDs...)
}

// BuildFromLockedSpecificationWithType is the explicit game-variant entrypoint.
// The legacy wrapper above keeps existing callers on the automatic selection
// path while the teacher-facing game picker can request RUNNER directly.
func BuildFromLockedSpecificationWithType(raw any, specificationID string, version int, requestedGameType string, materialFileIDs ...int64) (Spec, error) {
	var materialFileID int64
	if len(materialFileIDs) > 0 {
		materialFileID = materialFileIDs[0]
	}
	root, ok := raw.(map[string]any)
	if !ok {
		return Spec{}, errors.New("GAME_SPECIFICATION_INVALID")
	}
	plan, _ := root["plan"].(map[string]any)
	if plan == nil {
		plan = root
	}
	slides, ok := plan["slides"].([]any)
	if !ok || len(slides) == 0 {
		return Spec{}, errors.New("GAME_PLAN_EMPTY")
	}
	type slideData struct {
		title    string
		point    string
		source   SourceRef
		pageType string
	}
	var eligible []slideData
	var allSources []SourceRef
	for _, rawSlide := range slides {
		slide, ok := rawSlide.(map[string]any)
		if !ok {
			continue
		}
		title := strings.TrimSpace(fmt.Sprint(slide["title"]))
		points, _ := slide["points"].([]any)
		if title == "" || len(points) == 0 {
			continue
		}
		source, found := sourceFromSlide(slide, materialFileID)
		if !found {
			continue
		}
		allSources = appendUniqueSource(allSources, source)
		point := strings.TrimSpace(fmt.Sprint(points[0]))
		if point == "" {
			continue
		}
		eligible = append(eligible, slideData{title: title, point: point, source: source, pageType: strings.ToUpper(strings.TrimSpace(fmt.Sprint(slide["pageType"])))})
	}
	if len(eligible) == 0 || len(allSources) == 0 {
		return Spec{}, errors.New("GAME_MATERIAL_SOURCE_REQUIRED")
	}

	requestedGameType = strings.ToUpper(strings.TrimSpace(requestedGameType))
	gameType := GameTypeTrueFalse
	for _, item := range eligible {
		if item.pageType == "COMPARISON" && len(eligible) >= 2 {
			gameType = GameTypeSingleChoice
			break
		}
		if item.pageType == "PROCESS" && len(eligible) >= 2 {
			gameType = GameTypeMatching
		}
	}
	if requestedGameType != "" {
		switch requestedGameType {
		case GameTypeSingleChoice, GameTypeTrueFalse, GameTypeMatching, GameTypeRunner:
			gameType = requestedGameType
		default:
			return Spec{}, errors.New("GAME_TYPE_UNSUPPORTED")
		}
	}
	spec := Spec{
		Title:                      fmt.Sprintf("%s · 课堂互动挑战", eligible[0].title),
		Description:                "根据已锁定课件方案中的材料证据完成互动练习。",
		GameType:                   gameType,
		SourceSpecificationID:      specificationID,
		SourceSpecificationVersion: version,
		SourceRefs:                 allSources,
	}
	switch gameType {
	case GameTypeSingleChoice:
		for i := 0; i < len(eligible) && len(spec.Questions) < 3; i++ {
			correct := eligible[i]
			distractor := eligible[(i+1)%len(eligible)]
			options := []Option{{ID: "A", Text: correct.point}, {ID: "B", Text: distractor.point}}
			spec.Questions = append(spec.Questions, Question{
				ID: fmt.Sprintf("q-%d", len(spec.Questions)+1), Type: gameType,
				Prompt:  "关于“" + correct.title + "”，材料支持哪一项？",
				Options: options, Answer: []string{"A"},
				Explanation: "正确选项直接取自该页的材料依据；另一项来自不同教学页。",
				Source:      correct.source,
			})
		}
	case GameTypeMatching:
		pairs := make([]MatchPair, 0, 3)
		for i := 0; i < len(eligible) && len(pairs) < 3; i++ {
			item := eligible[i]
			pairs = append(pairs, MatchPair{ID: fmt.Sprintf("p-%d", i+1), Left: item.title, Right: item.point, Source: item.source})
		}
		spec.Questions = []Question{{ID: "q-1", Type: gameType, Prompt: "把课程单元与材料中的关键内容配对。", Pairs: pairs, Source: pairs[0].Source, Explanation: "每个配对项都来自锁定方案中的同页材料依据。"}}
	case GameTypeTrueFalse, GameTypeRunner:
		for i := 0; i < len(eligible) && len(spec.Questions) < 3; i++ {
			item := eligible[i]
			if gameType == GameTypeRunner && i%2 == 1 && len(eligible) >= 2 {
				distractor := eligible[(i+1)%len(eligible)]
				spec.Questions = append(spec.Questions, Question{
					ID: fmt.Sprintf("q-%d", len(spec.Questions)+1), Type: GameTypeSingleChoice,
					Prompt:  "关于“" + item.title + "”，材料支持哪一项？",
					Options: []Option{{ID: "A", Text: item.point}, {ID: "B", Text: distractor.point}}, Answer: []string{"A"},
					Explanation: "正确选项直接取自该教学页的材料依据。", Source: item.source,
				})
				continue
			}
			spec.Questions = append(spec.Questions, Question{
				ID: fmt.Sprintf("q-%d", len(spec.Questions)+1), Type: GameTypeTrueFalse,
				Prompt: "判断：" + item.point, Options: []Option{{ID: "TRUE", Text: "正确"}, {ID: "FALSE", Text: "错误"}},
				Answer: []string{"TRUE"}, Explanation: "该判断对应锁定方案中引用的材料内容。", Source: item.source,
			})
		}
	}
	if len(spec.Questions) == 0 {
		return Spec{}, errors.New("GAME_QUESTIONS_EMPTY")
	}
	return spec, nil
}

func sourceFromSlide(slide map[string]any, materialFileID int64) (SourceRef, bool) {
	for _, key := range []string{"sourceRefs", "sources"} {
		items, _ := slide[key].([]any)
		for _, raw := range items {
			if locator, ok := raw.(string); ok {
				locator = strings.TrimSpace(locator)
				if materialFileID > 0 && locator != "" {
					return SourceRef{FileID: materialFileID, Locator: locator}, true
				}
				continue
			}
			ref, ok := raw.(map[string]any)
			if !ok {
				continue
			}
			fileID := numberValue(ref["fileId"])
			if fileID == 0 {
				fileID = numberValue(ref["id"])
			}
			locator := strings.TrimSpace(fmt.Sprint(ref["locator"]))
			if fileID > 0 && locator != "" {
				return SourceRef{FileID: fileID, Locator: locator, Claim: strings.TrimSpace(fmt.Sprint(ref["claim"]))}, true
			}
		}
	}
	return SourceRef{}, false
}

func appendUniqueSource(items []SourceRef, source SourceRef) []SourceRef {
	for _, item := range items {
		if item.FileID == source.FileID && item.Locator == source.Locator {
			return items
		}
	}
	return append(items, source)
}

func numberValue(value any) int64 {
	switch n := value.(type) {
	case int:
		return int64(n)
	case int64:
		return n
	case float64:
		return int64(n)
	case json.Number:
		parsed, _ := n.Int64()
		return parsed
	case string:
		parsed, _ := strconv.ParseInt(strings.TrimSpace(n), 10, 64)
		return parsed
	default:
		return 0
	}
}

// Render returns a standalone, dependency-free HTML game. The JSON payload is
// embedded as data, while all visible values are escaped before insertion.
func Render(spec Spec) ([]byte, error) {
	encoded, err := json.Marshal(spec)
	if err != nil {
		return nil, fmt.Errorf("GAME_SPEC_ENCODE_FAILED: %w", err)
	}
	data := strings.NewReplacer("<", "\\u003c", ">", "\\u003e", "&", "\\u0026").Replace(string(encoded))
	title := html.EscapeString(spec.Title)
	description := html.EscapeString(spec.Description)
	markup := fmt.Sprintf(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>%s</title><style>
:root{font-family:system-ui,-apple-system,"Microsoft YaHei",sans-serif;color:#172033;background:#f5f7ff}*{box-sizing:border-box}body{margin:0;min-height:100vh;background:linear-gradient(135deg,#eef1ff,#fff 60%%);display:flex;justify-content:center}.app{width:min(900px,100%%);padding:32px 20px 48px}.hero,.card{background:#fff;border:1px solid #e4e8f5;border-radius:22px;box-shadow:0 18px 45px rgba(71,76,145,.12)}.hero{padding:28px 30px;margin-bottom:18px}.eyebrow{color:#6757e8;font-size:13px;font-weight:700;letter-spacing:.12em}.hero h1{margin:9px 0 8px;font-size:clamp(24px,4vw,38px)}.hero p{margin:0;color:#68738c;line-height:1.65}.card{padding:26px;margin-top:18px}.progress{color:#68738c;font-size:14px;margin-bottom:14px}.prompt{font-size:22px;line-height:1.5;margin:0 0 20px}.choices{display:grid;gap:12px}.choice,.match-select,button{font:inherit}.choice{width:100%%;text-align:left;border:1px solid #dfe4f2;background:#fbfcff;color:#172033;padding:15px 17px;border-radius:14px;cursor:pointer;transition:.2s}.choice:hover{border-color:#7566ed;transform:translateY(-1px)}.choice.selected{border-color:#6757e8;background:#f0eeff}.choice.correct{border-color:#32a36a;background:#e9fbf1}.choice.wrong{border-color:#df5d70;background:#fff0f2}.actions{display:flex;gap:10px;margin-top:20px;flex-wrap:wrap}button{border:0;border-radius:12px;padding:11px 18px;cursor:pointer;background:#6757e8;color:#fff;font-weight:700}button.secondary{background:#eef0f8;color:#44506c}.feedback{margin-top:18px;padding:15px;border-radius:14px;background:#f5f7ff;color:#46536f;line-height:1.6}.source{margin-top:15px;font-size:12px;color:#7a849b}.finished{text-align:center}.finished h2{margin-top:0;color:#6757e8}.match-grid{display:grid;gap:12px}.match-row{display:grid;grid-template-columns:1fr 1fr;gap:12px;align-items:center}.match-row span{font-weight:700}.match-select{width:100%%;padding:12px;border:1px solid #dfe4f2;border-radius:12px;background:#fff}.score{font-size:18px;color:#273457;margin:8px 0 20px}</style></head><body><main class="app"><section class="hero"><div class="eyebrow">LESSONFORGE · 课堂互动</div><h1>%s</h1><p>%s</p></section><section id="root"></section><script id="game-data" type="application/json">%s</script><script>
const spec=JSON.parse(document.getElementById('game-data').textContent);const root=document.getElementById('root');let index=0,score=0,answers=[];const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));function render(){if(index>=spec.questions.length){root.innerHTML='<section class="card finished"><h2>互动完成</h2><p class="score">得分：'+score+' / '+spec.questions.length+'</p><p>你已完成本次基于课程材料的互动练习。</p><button onclick="location.reload()">再来一次</button></section>';return}const q=spec.questions[index];if(q.type==='MATCHING'){renderMatch(q);return}let selected='';root.innerHTML='<section class="card"><div class="progress">第 '+(index+1)+' / '+spec.questions.length+' 题</div><h2 class="prompt">'+esc(q.prompt)+'</h2><div class="choices">'+q.options.map(o=>'<button class="choice" data-id="'+esc(o.id)+'">'+esc(o.text)+'</button>').join('')+'</div><div class="actions"><button id="submit" disabled>提交答案</button></div><div id="feedback"></div><div class="source">材料来源：文件 '+esc(q.source.fileId)+' · '+esc(q.source.locator)+'</div></section>';document.querySelectorAll('.choice').forEach(b=>b.onclick=()=>{selected=b.dataset.id;document.querySelectorAll('.choice').forEach(x=>x.classList.remove('selected'));b.classList.add('selected');document.getElementById('submit').disabled=false});document.getElementById('submit').onclick=()=>{const ok=q.answer.includes(selected);if(ok)score++;document.querySelectorAll('.choice').forEach(b=>{if(q.answer.includes(b.dataset.id))b.classList.add('correct');else if(b.dataset.id===selected)b.classList.add('wrong')});document.getElementById('feedback').innerHTML='<div class="feedback">'+(ok?'回答正确。':'再看一看材料。')+' '+esc(q.explanation)+'<div class="actions"><button onclick="index++;render()">下一题</button></div></div>';document.getElementById('submit').disabled=true}}function renderMatch(q){root.innerHTML='<section class="card"><div class="progress">配对练习</div><h2 class="prompt">'+esc(q.prompt)+'</h2><div class="match-grid">'+q.pairs.map(p=>'<div class="match-row"><span>'+esc(p.left)+'</span><select class="match-select" data-id="'+esc(p.id)+'"><option value="">请选择</option>'+q.pairs.map(x=>'<option value="'+esc(x.id)+'">'+esc(x.right)+'</option>').join('')+'</select></div>').join('')+'</div><div class="actions"><button id="match-submit">提交配对</button></div><div id="feedback"></div><div class="source">材料来源：文件 '+esc(q.source.fileId)+' · '+esc(q.source.locator)+'</div></section>';document.getElementById('match-submit').onclick=()=>{const values=[...document.querySelectorAll('.match-select')].map(x=>x.value);const ok=q.pairs.every((p,i)=>values[i]===p.id);if(ok)score++;document.getElementById('feedback').innerHTML='<div class="feedback">'+(ok?'全部配对正确。':'部分配对不正确，请依据课程单元和关键内容重新判断。')+' '+esc(q.explanation)+'<div class="actions"><button onclick="index++;render()">下一题</button></div></div>';document.getElementById('match-submit').disabled=true}}render();
</script></main></body></html>`, title, title, description, data)
	if spec.GameType == GameTypeRunner {
		runnerCSS := `.runner-card{overflow:hidden}.runner-top{display:flex;justify-content:space-between;gap:18px;align-items:flex-start}.runner-kicker{color:#6757e8;font-size:11px;font-weight:800;letter-spacing:.12em}.runner-title{margin:7px 0 4px;font-size:22px}.runner-top p{margin:0;color:#68738c;line-height:1.55}.runner-score{min-width:78px;padding:10px 12px;border-radius:14px;background:#172033;color:#fff;text-align:center;font-weight:800}.runner-hud{display:flex;justify-content:space-between;gap:12px;margin:20px 0 9px;color:#68738c;font-size:13px}.runner-arena{position:relative;height:250px;overflow:hidden;border:1px solid #dce3f4;border-radius:18px;background:linear-gradient(#dff1ff 0%,#f7fbff 67%,#d7e8ff 67%,#d7e8ff 100%);outline:0;isolation:isolate}.runner-arena:focus{box-shadow:0 0 0 3px rgba(103,87,232,.18)}.runner-ground{position:absolute;right:0;bottom:0;left:0;height:14px;background:repeating-linear-gradient(90deg,#6678a0 0 18px,transparent 18px 34px);opacity:.45}.runner-hill{position:absolute;bottom:14px;width:280px;height:82px;border-radius:50% 50% 0 0;background:#c2d6ef;opacity:.58}.runner-hill--one{left:-42px}.runner-hill--two{right:-56px;bottom:18px;transform:scale(.72)}.runner-cloud{position:absolute;width:82px;height:20px;border-radius:20px;background:rgba(255,255,255,.78);opacity:.8}.runner-cloud:before,.runner-cloud:after{position:absolute;border-radius:50%;background:inherit;content:""}.runner-cloud:before{width:34px;height:34px;bottom:1px;left:14px}.runner-cloud:after{width:28px;height:28px;right:14px;bottom:0}.runner-cloud--one{top:34px;left:17%;transform:scale(.82)}.runner-cloud--two{top:74px;right:13%;transform:scale(.58)}.runner-player{position:absolute;z-index:3;bottom:14px;left:72px;width:42px;height:45px;clip-path:polygon(0 18%,18% 18%,18% 0,68% 0,68% 13%,100% 13%,100% 68%,80% 68%,80% 100%,59% 100%,59% 76%,37% 76%,37% 100%,13% 100%,13% 76%,0 76%);background:#6757e8;box-shadow:inset -7px -5px 0 rgba(42,30,142,.2);will-change:transform}.runner-player.is-running{animation:runner-bob .22s linear infinite alternate}@keyframes runner-bob{to{margin-bottom:2px}}.runner-player:before{position:absolute;top:7px;right:4px;width:6px;height:6px;border-radius:50%;background:#fff;content:""}.runner-player:after{position:absolute;right:3px;bottom:0;width:7px;height:13px;background:#172033;box-shadow:-21px 0 0 #172033;content:""}.runner-obstacle{position:absolute;z-index:2;bottom:14px;width:24px;height:48px;border-radius:8px 8px 3px 3px;background:#32a36a;box-shadow:inset 6px 0 0 rgba(18,102,62,.18);will-change:transform}.runner-obstacle:before{position:absolute;top:17px;left:-11px;width:16px;height:9px;border-radius:7px 0 0 7px;background:#32a36a;content:""}.runner-obstacle--wide{width:42px}.runner-obstacle--wide:after{position:absolute;top:7px;right:-9px;width:16px;height:9px;border-radius:0 7px 7px 0;background:#32a36a;content:""}.runner-overlay{position:absolute;z-index:5;inset:0;display:grid;place-content:center;text-align:center;background:rgba(245,249,255,.72);backdrop-filter:blur(3px)}.runner-overlay[hidden],.runner-question[hidden]{display:none}.runner-overlay strong{display:block;margin-bottom:6px;font-size:22px;color:#172033}.runner-overlay span{display:block;margin-bottom:14px;color:#68738c}.runner-controls{display:flex;gap:9px;align-items:center;margin-top:14px}.runner-controls small{margin-left:auto;color:#7a849b}.runner-question{margin-top:16px;padding:17px;border:1px solid #e5d899;border-radius:16px;background:#fffdf0}.runner-question__tag{color:#8a6e18;font-size:12px;font-weight:800}.runner-question h3{margin:8px 0 14px;font-size:19px;line-height:1.45}.runner-question .choices{gap:8px}.runner-question .choice{padding:11px 13px}.runner-question .source{margin-top:12px}.runner-question .feedback{margin-top:13px;background:#fff8d9}.runner-hearts{color:#df5d70;letter-spacing:.08em}`
		runnerCSS += `.runner-player{width:68px;height:62px;clip-path:none;background:transparent;box-shadow:none}.runner-player:before,.runner-player:after{display:none;content:none}.runner-dino-tail,.runner-dino-body,.runner-dino-neck,.runner-dino-head,.runner-dino-arm,.runner-dino-leg{position:absolute;display:block;background:#6757e8}.runner-dino-tail{bottom:22px;left:0;width:28px;height:17px;clip-path:polygon(100% 20%,58% 20%,58% 0,24% 0,24% 38%,0 38%,0 74%,42% 74%,42% 100%,100% 100%)}.runner-dino-body{bottom:12px;left:17px;width:37px;height:34px;border-radius:7px 12px 7px 6px;background:#6757e8;box-shadow:inset -5px -5px 0 rgba(42,30,142,.2)}.runner-dino-neck{bottom:31px;left:39px;width:15px;height:25px;border-radius:6px 7px 2px 2px}.runner-dino-head{right:0;bottom:48px;width:29px;height:21px;border-radius:9px 11px 5px 4px}.runner-dino-head:before{position:absolute;top:7px;right:5px;width:5px;height:5px;border-radius:50%;background:#fff;content:""}.runner-dino-head:after{position:absolute;right:-5px;bottom:3px;width:8px;height:5px;border-radius:0 4px 4px 0;background:#4c3fc0;content:""}.runner-dino-arm{right:4px;bottom:27px;width:16px;height:6px;border-radius:4px;transform:rotate(16deg);transform-origin:left center}.runner-dino-leg{bottom:0;width:8px;height:17px;background:#172033}.runner-dino-leg--one{left:23px}.runner-dino-leg--two{left:42px}.runner-player.is-running .runner-dino-leg--one{animation:dino-leg-one .22s linear infinite alternate}.runner-player.is-running .runner-dino-leg--two{animation:dino-leg-two .22s linear infinite alternate}@keyframes dino-leg-one{to{transform:translateY(-2px) rotate(12deg)}}@keyframes dino-leg-two{to{transform:translateY(1px) rotate(-12deg)}}`
		runnerCSS += `body{background:radial-gradient(circle at 84% 4%,rgba(255,220,138,.38),transparent 25%),linear-gradient(180deg,#edf5ff 0%,#ffffff 58%,#edf3ff 100%)}.runner-card{background:rgba(255,255,255,.9);backdrop-filter:blur(10px)}.runner-arena{background:linear-gradient(180deg,#c9e8ff 0%,#f1f9ff 61%,#e4f1d6 61%,#cce4b8 100%);box-shadow:inset 0 10px 30px rgba(255,255,255,.38),0 12px 28px rgba(81,116,165,.16)}.runner-sun{position:absolute;z-index:0;top:22px;right:15%;width:48px;height:48px;border-radius:50%;background:#ffe39a;box-shadow:0 0 0 10px rgba(255,227,154,.2),0 0 34px rgba(255,213,112,.45)}.runner-mountain{position:absolute;z-index:0;bottom:14px;width:310px;height:112px;opacity:.7;clip-path:polygon(0 100%,0 65%,18% 42%,31% 58%,55% 15%,74% 53%,88% 31%,100% 57%,100% 100%);background:#b4cee3}.runner-mountain--one{left:-28px}.runner-mountain--two{right:-34px;transform:scale(.76);transform-origin:bottom right;background:#a9c7b7;opacity:.62}.runner-sun,.runner-mountain{pointer-events:none}`
		runnerCSS += `.runner-ground{height:18px;background:linear-gradient(180deg,#78aa62 0,#91c176 22%,#b8d68e 22%,#a9c77b 100%);opacity:1;box-shadow:0 -2px 0 rgba(73,125,70,.24)}.runner-ground:before{position:absolute;top:-3px;right:0;left:0;height:5px;background:repeating-linear-gradient(90deg,#6d9f5b 0 13px,#86b96c 13px 25px,#74a862 25px 39px);content:""}.runner-ground:after{position:absolute;right:0;bottom:4px;left:0;height:3px;background:repeating-linear-gradient(90deg,rgba(111,145,82,.35) 0 4px,transparent 4px 23px);content:""}.runner-arena .runner-player,.runner-arena .runner-obstacle{bottom:18px}.runner-arena .runner-mountain{bottom:18px}`
		runnerJS := `function renderRunner(){const qList=spec.questions||[];root.innerHTML='<section class="card runner-card"><div class="runner-top"><div><div class="runner-kicker">材料闯关</div><h2 class="runner-title">'+esc(spec.title)+'</h2><p>自动奔跑，跳过障碍；撞到障碍后完成一个材料问题，答对才能继续。</p></div><div class="runner-score" id="runner-score">0 米</div></div><div class="runner-hud"><span id="runner-stage">准备出发</span><span><b class="runner-hearts" id="runner-hearts">♥♥♥</b> · <span id="runner-progress">知识点 1 / '+qList.length+'</span></span></div><div class="runner-arena" id="runner-arena" tabindex="0" aria-label="小恐龙闯关区域"><div class="runner-cloud runner-cloud--one"></div><div class="runner-cloud runner-cloud--two"></div><div class="runner-player" id="runner-player"></div><div class="runner-ground"></div><div id="runner-obstacles"></div><div class="runner-overlay" id="runner-overlay"><div><strong>准备开始</strong><span>按空格或点击“跳跃”躲开障碍物</span><button id="runner-start">开始闯关</button></div></div></div><div class="runner-controls"><button id="runner-jump">跳跃</button><button id="runner-restart" class="secondary">重新开始</button><small>键盘：空格 / ↑</small></div><div class="runner-question" id="runner-question" hidden></div></section>';const arena=document.getElementById('runner-arena'),player=document.getElementById('runner-player'),obstacleLayer=document.getElementById('runner-obstacles'),overlay=document.getElementById('runner-overlay'),questionBox=document.getElementById('runner-question'),stage=document.getElementById('runner-stage'),scoreBox=document.getElementById('runner-score'),heartsBox=document.getElementById('runner-hearts'),progressBox=document.getElementById('runner-progress');let running=false,asking=false,finished=false,raf=0,last=0,playerY=0,playerV=0,distance=0,hearts=3,checkpoint=0,nextSpawn=950,obstacles=[];const playerX=72,gravity=1700,jumpPower=655;function update(){scoreBox.textContent=Math.floor(distance)+' 米';heartsBox.textContent='♥'.repeat(Math.max(0,hearts))+'♡'.repeat(Math.max(0,3-hearts));progressBox.textContent='知识点 '+Math.min(checkpoint+1,qList.length)+' / '+qList.length;player.style.transform='translateY('+(-playerY)+'px)'}function jump(){if(!running||asking||finished||playerY>1)return;playerV=jumpPower;arena.classList.add('is-jumping');setTimeout(()=>arena.classList.remove('is-jumping'),180)}function spawn(){const node=document.createElement('div');node.className='runner-obstacle';const obstacle={node,x:arena.clientWidth+30,width:24,height:48+Math.random()*20};node.style.height=obstacle.height+'px';obstacleLayer.appendChild(node);obstacles.push(obstacle)}function clearObstacles(){obstacles.forEach(o=>o.node.remove());obstacles=[]}function hit(o){return o.x<playerX+38&&o.x+o.width>playerX+4&&playerY<o.height-7}function begin(){if(finished){location.reload();return}if(asking)return;running=true;overlay.hidden=true;stage.textContent='奔跑中';last=performance.now();arena.focus();cancelAnimationFrame(raf);raf=requestAnimationFrame(tick)}function finish(win){running=false;asking=false;finished=true;cancelAnimationFrame(raf);questionBox.hidden=true;overlay.hidden=false;overlay.innerHTML='<div><strong>'+ (win?'闯关成功！':'闯关结束') +'</strong><span>'+ (win?'你完成了全部材料检查点。':'生命值耗尽，再复习一次材料后再来挑战。') +'</span><button id="runner-overlay-restart">'+(win?'再来一次':'重新开始')+'</button></div>';document.getElementById('runner-overlay-restart').onclick=()=>location.reload();stage.textContent=win?'已完成':'需要复习';update()}function showQuestion(){running=false;asking=true;cancelAnimationFrame(raf);const q=qList[checkpoint];if(!q){finish(true);return}stage.textContent='知识检查点';questionBox.hidden=false;questionBox.innerHTML='<div class="runner-question__tag">第 '+(checkpoint+1)+' 个检查点</div><h3>'+esc(q.prompt)+'</h3><div class="choices">'+(q.options||[]).map(o=>'<button class="choice" data-id="'+esc(o.id)+'">'+esc(o.text)+'</button>').join('')+'</div><div class="runner-feedback" id="runner-feedback"></div><div class="source">材料来源：文件 '+esc(q.source.fileId)+' · '+esc(q.source.locator)+'</div>';questionBox.querySelectorAll('.choice').forEach(button=>button.onclick=()=>answer(button.dataset.id,q,questionBox));questionBox.scrollIntoView({behavior:'smooth',block:'nearest'})}function answer(id,q,box){const buttons=[...box.querySelectorAll('.choice')];buttons.forEach(button=>button.disabled=true);const ok=(q.answer||[]).includes(id);if(ok){checkpoint++;distance+=100;const feedback=box.querySelector('#runner-feedback');feedback.innerHTML='<div class="feedback">回答正确，继续前进。 '+esc(q.explanation)+'</div><div class="actions"><button id="runner-continue">继续奔跑</button></div>';document.getElementById('runner-continue').onclick=()=>{if(checkpoint>=qList.length){finish(true);return}questionBox.hidden=true;asking=false;nextSpawn=700;begin()}}else{hearts--;update();const feedback=box.querySelector('#runner-feedback');feedback.innerHTML='<div class="feedback">暂时答错了，请回忆材料后再试。 '+esc(q.explanation)+'</div>'+ (hearts>0?'<div class="actions"><button id="runner-retry">再试一次</button></div>':'');if(hearts>0){document.getElementById('runner-retry').onclick=()=>{buttons.forEach(button=>{button.disabled=false;button.classList.remove('wrong')});feedback.innerHTML=''}}else{finish(false)}}update()}function tick(now){if(!running||asking||finished)return;const dt=Math.min((now-last)/1000,.04);last=now;playerV-=gravity*dt;playerY=Math.max(0,playerY+playerV*dt);if(playerY===0)playerV=0;const speed=260+Math.min(150,distance*.12);distance+=speed*dt*.06;nextSpawn-=dt*1000;if(nextSpawn<=0){spawn();nextSpawn=1050+Math.random()*850}for(let i=obstacles.length-1;i>=0;i--){const obstacle=obstacles[i];obstacle.x-=speed*dt;obstacle.node.style.transform='translateX('+obstacle.x+'px)';if(hit(obstacle)){obstacle.node.remove();obstacles.splice(i,1);showQuestion();return}if(obstacle.x+obstacle.width<0){obstacle.node.remove();obstacles.splice(i,1)}}update();raf=requestAnimationFrame(tick)}document.getElementById('runner-start').onclick=begin;document.getElementById('runner-jump').onclick=jump;document.getElementById('runner-restart').onclick=()=>location.reload();arena.addEventListener('keydown',event=>{if(event.code==='Space'||event.code==='ArrowUp'){event.preventDefault();if(!running&&!asking)begin();else jump()}});arena.addEventListener('click',event=>{if(event.target===arena&&!running&&!asking)begin()});update()}`
		runnerJS = strings.ReplaceAll(runnerJS, `node.className='runner-obstacle';const obstacle={node,x:arena.clientWidth+30,width:24,height:48+Math.random()*20};`, `const wide=Math.random()<.28;node.className='runner-obstacle'+(wide?' runner-obstacle--wide':'');const obstacle={node,x:arena.clientWidth+30,width:wide?42:24,height:48+Math.random()*20};`)
		runnerJS = strings.ReplaceAll(runnerJS, `<div class=\"runner-player\" id=\"runner-player\"></div>`, `<div class=\"runner-player\" id=\"runner-player\"><i class=\"runner-dino-tail\"></i><i class=\"runner-dino-body\"></i><i class=\"runner-dino-neck\"></i><i class=\"runner-dino-head\"></i><i class=\"runner-dino-arm\"></i><i class=\"runner-dino-leg runner-dino-leg--one\"></i><i class=\"runner-dino-leg runner-dino-leg--two\"></i></div>`)
		runnerJS = strings.ReplaceAll(runnerJS, `<div class="runner-player" id="runner-player"></div>`, `<div class="runner-player" id="runner-player"><i class="runner-dino-tail"></i><i class="runner-dino-body"></i><i class="runner-dino-neck"></i><i class="runner-dino-head"></i><i class="runner-dino-arm"></i><i class="runner-dino-leg runner-dino-leg--one"></i><i class="runner-dino-leg runner-dino-leg--two"></i></div>`)
		runnerDinosaurMarkup := `<img class="runner-dino-sprite" alt="" src="` + runnerDinosaurDataURI + `">`
		runnerJS = strings.ReplaceAll(runnerJS, `<i class="runner-dino-tail"></i><i class="runner-dino-body"></i><i class="runner-dino-neck"></i><i class="runner-dino-head"></i><i class="runner-dino-arm"></i><i class="runner-dino-leg runner-dino-leg--one"></i><i class="runner-dino-leg runner-dino-leg--two"></i>`, runnerDinosaurMarkup)
		runnerJS = strings.ReplaceAll(runnerJS, `<div class="runner-cloud runner-cloud--two"></div>`, `<div class="runner-cloud runner-cloud--two"></div><div class="runner-sun"></div><div class="runner-mountain runner-mountain--one"></div><div class="runner-mountain runner-mountain--two"></div>`)
		runnerJS = strings.ReplaceAll(runnerJS, `running=true;overlay.hidden=true;`, `running=true;player.classList.add('is-running');overlay.hidden=true;`)
		runnerJS = strings.ReplaceAll(runnerJS, `running=false;asking=false;finished=true;`, `running=false;player.classList.remove('is-running');asking=false;finished=true;`)
		runnerJS = strings.ReplaceAll(dualRunnerTemplate, "__DINO__", runnerDinosaurMarkup)
		runnerCSS += runnerDinosaurCSS + runnerDinosaurSpriteCSS + runnerDuoCSS
		markup = strings.Replace(markup, "</style>", runnerCSS+"</style>", 1)
		markup = strings.Replace(markup, "render();\n</script>", runnerJS+"if(spec.gameType==='RUNNER'){renderRunner()}else{render()};\n</script>", 1)
	}
	return []byte(markup), nil
}
