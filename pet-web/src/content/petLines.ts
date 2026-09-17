import type { PetStatus, Species } from '@/types/pet'

/**
 * 性格台词库（PRD 2.8）。
 *
 * 三条规则，对应 PRD 的三句话：
 *
 * 1. 台词**按物种和状态分开**：猫傲娇、狗热情、龙中二，同一个状态三只说法不同。
 * 2. 每个桶**至少 3 句**，且**不连续重复** —— 由 `createSpeechDirector` 保证。
 * 3. 操作、升级、生病、进化和回访**优先播场景台词**，其余时候按当前状态说话。
 *
 * 台词是固定文案，不调用任何在线模型（AGENTS.md 第 9 节）。
 *
 * 状态表和场景表**分开**：`SICK` 两边都有，合在一张表里会撞键，
 * 而且两者的触发时机本来就不同 —— 一个是"平时病着"，一个是"刚刚病倒"。
 */

/** 场景台词。发生具体事件时优先于状态台词。 */
export type SpeechScene =
  | 'FEED'
  | 'PLAY'
  | 'CLEAN'
  | 'SLEEP'
  | 'WAKE'
  | 'LEVEL_UP'
  | 'EVOLVE'
  | 'SICK'
  | 'RETURN'

export interface SpeechContext {
  species: Species
  /** 场景优先；没有场景就按 `status` 说话。 */
  scene?: SpeechScene
  status?: PetStatus
}

/** 平时按状态说的话。 */
export const PET_STATUS_LINES: Record<Species, Record<PetStatus, readonly string[]>> = {
  // ---------------------------------------------------------- 猫：傲娇、慵懒，嘴硬
  CAT: {
    NORMAL: ['今天也还算凑合。', '别盯着看，我会不好意思的……才怪。', '阳光不错，适合打盹。'],
    HUNGRY: ['碗是空的。你自己看着办。', '……饿了。只是有一点。', '不给吃的就别摸我。'],
    DIRTY: ['毛乱了。这都怪你。', '我要舔很久才能弄干净……', '别碰，我现在一身灰。'],
    TIRED: ['眼皮很重……别吵。', '我要睡了，谁也别拦。', '再撑一会儿也不是不行……'],
    SAD: ['……没什么。别管我。', '今天不想理任何人。', '你很久没来了。'],
    SICK: ['难受……先别碰我。', '鼻子是热的。', '我不想动。'],
    SLEEPING: ['zzz……', '（翻了个身，尾巴抽了一下）', '梦里有很多鱼。'],
  },

  // ---------------------------------------------------------- 狗：热情、直白、黏人
  DOG: {
    NORMAL: ['今天也超有精神！', '你回来啦，我一直在这儿等着！', '一起玩点什么吧！'],
    HUNGRY: ['肚子在叫！你听到了吗！', '吃饭时间到了吧？到了吧？', '我什么都能吃，真的！'],
    DIRTY: ['我滚了泥巴！好玩！你看！', '身上有点痒痒的。', '洗澡也能玩水吧？'],
    TIRED: ['跑不动了……但还能再玩一下！', '眼皮在打架了。', '让我歇一小会儿就好！'],
    SAD: ['你是不是把我忘了……', '尾巴都摇不起来了。', '陪陪我好吗？'],
    SICK: ['我好像……不太舒服。', '呜……', '别走开好不好。'],
    SLEEPING: ['呼……呼……', '（尾巴还在轻轻摇）', '梦里在追球……'],
  },

  // ---------------------------------------------------------- 龙：中二、自大，其实很黏人
  DRAGON: {
    NORMAL: ['本龙的威仪，你可要好好看着。', '今天的鳞片也很亮。', '嗯，状态不错，勉强配得上我。'],
    HUNGRY: ['凡人，本龙需要贡品。', '饿到龙威都撑不住了……', '我不催你。我只是盯着你。'],
    DIRTY: ['龙鳞上沾了灰，成何体统。', '快一点，趁我还没生气。', '擦亮一点，才像条龙。'],
    TIRED: ['本龙……只是稍微有点倦。', '龙也需要休整，这很正常。', '别笑我。累就是累。'],
    SAD: ['龙也会有低落的时候，很奇怪吗。', '我现在不想说话。', '……你还在啊。'],
    SICK: ['龙的体质不会生病……咳。', '只是有点冷而已。', '别慌。我没事。'],
    SLEEPING: ['（龙的鼾声有点大）', '梦里我统治了整个世界……', '（尾巴把自己盘了起来）'],
  },
}

/** 发生具体事件时说的话。 */
export const PET_SCENE_LINES: Record<Species, Record<SpeechScene, readonly string[]>> = {
  CAT: {
    FEED: ['……还行。下次多给点。', '哼，算你有心。', '吃完了，碗收走。'],
    PLAY: ['就玩一下，别得意。', '这个……还挺有意思的。', '再来一次也不是不行。'],
    CLEAN: ['手法还行。', '毛顺了，勉强可以见人。', '下次记得轻一点。'],
    SLEEP: ['终于。别吵我。', '那我睡了，谁叫醒我谁负责。', '晚安。就这一次。'],
    WAKE: ['……吵死了。', '谁允许你叫醒我的？', '啊……刚梦到一条很大的鱼。'],
    LEVEL_UP: ['又变强了一点。理所当然。', '看到了吗？这就是我。', '别高兴太早，这只是开始。'],
    EVOLVE: ['……长开了。别盯着看。', '看清楚了，这才是我真正的样子。', '哼，现在知道谁最厉害了吧。'],
    SICK: ['……我好像有点不对劲。', '别慌，我只是……有点冷。', '今天不陪你玩了。'],
    RETURN: ['你还知道回来。', '……等了你很久。才怪。', '走了这么久，上哪去了？'],
  },
  DOG: {
    FEED: ['好吃！还要！', '你是世界上最好的主人！', '我全吃光了，一粒都没剩！'],
    PLAY: ['太好玩了！！', '再来再来！我还没累！', '接住啦！你看你看！'],
    CLEAN: ['香香的！你闻！', '洗干净是不是更帅了！', '水花溅到你身上啦，哈哈！'],
    SLEEP: ['那我睡啦，晚安！', '做个好梦……你也早点睡。', '我就在这儿睡，你一叫我就醒。'],
    WAKE: ['早！我醒了！我们去玩吧！', '我醒啦！我一叫就醒！', '早上好！现在几点了！'],
    LEVEL_UP: ['我升级啦！我是不是很棒！', '我还能变得更强！', '你看到了吗？我刚刚变强了！'],
    EVOLVE: ['我长大了！！你看你看！', '现在的我能跑得更远！', '我会保护你的，一定！'],
    SICK: ['我好像生病了……', '头有点晕，是不是我跑太多了。', '你别担心，我会好起来的。'],
    RETURN: ['你回来啦！！我好想你！', '我一直守在门口等你！', '来来来，快摸摸我！'],
  },
  DRAGON: {
    FEED: ['贡品合格，本龙收下了。', '勉强配得上我的身份。', '再来一份也不是不行。'],
    PLAY: ['本龙陪你玩，是你的荣幸。', '哈哈哈，凡人，你输了。', '还挺好玩的……我什么都没说。'],
    CLEAN: ['鳞片又亮了三分。', '这才配得上龙的身份。', '擦得不错。赏。'],
    SLEEP: ['本龙要小憩片刻。退下吧。', '守好这里，别让人打扰我。', '梦里见，凡人。'],
    WAKE: ['谁准你打断龙的清梦？', '……几点了。', '哼，看在你的份上。'],
    LEVEL_UP: ['本龙又强了一分，理应如此。', '感觉到了吗？这就是成长。', '别大惊小怪，龙本来就该这样。'],
    EVOLVE: ['觉醒吧——本龙的真姿！', '终于有点龙的样子了。', '跪下吧，凡人。我是说……你可以摸摸看。'],
    SICK: ['龙是不会病的……大概。', '这点小毛病，不足挂齿。', '别用那种眼神看我。'],
    RETURN: ['凡人，你让本龙等太久了。', '回来就好……我是说，龙并不在意。', '这段时间，本龙一直看着门。'],
  },
}

/** 既没有场景也没有状态时的兜底。 */
const FALLBACK = '……'

export interface SpeechDirector {
  /** 挑一句当前该说的话，保证不和上一条重复。 */
  next(context: SpeechContext): string
  /** 清空"上一条"记录，重新领养时用。 */
  reset(): void
}

/**
 * 台词调度器。
 *
 * 两件事：**选表**（场景优先于状态）和**去重**（同一个桶里不连续说同一句）。
 * 随机源从外面注入，测试里可以换成固定序列。
 */
export function createSpeechDirector(random: () => number = Math.random): SpeechDirector {
  /** 每个桶上一次说过的那句。键带场景/状态前缀，SICK 两边各记各的。 */
  const lastLine = new Map<string, string>()

  function next(context: SpeechContext): string {
    if (context.scene) {
      return pick(`${context.species}:scene:${context.scene}`, PET_SCENE_LINES[context.species][context.scene])
    }
    if (context.status) {
      return pick(`${context.species}:status:${context.status}`, PET_STATUS_LINES[context.species][context.status])
    }
    return FALLBACK
  }

  function pick(key: string, lines: readonly string[]): string {
    if (lines.length === 0) {
      return FALLBACK
    }
    const previous = lastLine.get(key)
    // 把上一条排除掉再选。桶里不止一句（测试盯着这个前提），就不可能连说两次。
    const candidates = lines.length > 1 ? lines.filter((line) => line !== previous) : lines
    const picked = candidates[Math.floor(random() * candidates.length)] ?? candidates[0] ?? FALLBACK

    lastLine.set(key, picked)
    return picked
  }

  function reset(): void {
    lastLine.clear()
  }

  return { next, reset }
}
