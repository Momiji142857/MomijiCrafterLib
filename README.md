# MultiCrafterLib_Momiji

为 Mindustry Java 模组作者提供的多功能工厂方块库，可通过 JSON 配置快速使用。

## 现有方块

**MultiCrafter_Momiji** —— 多配方切换工厂，一次启用一个配方，支持物品、液体、电力、热量、载荷的任意组合。自动适配原版单位建造速度与消耗规则。

## 使用方式

在自己的模组文件夹下建个新的文件夹, 改名为"MultiCrafter_Momiji". 把名字中带"MultiCrafter_Momiji"的文件全挪进去.

如果你的模组是java的, 并且想用json写多配方库, 可以直接在Mod主文件里的"loadContent()"函数里加上"ClassMap.classes.put("MultiCrafter_Momiji", MultiCrafter_Momiji.class);"

如果是json或js的, 需要在mod.json里写上: "java": true, 和 "main": "MultiCrafter_Momiji.LibMod",

然后把名字带有"LibMod"的文件也挪到MultiCrafter_Momiji文件夹里.

然后想用json或java写都可以了

## 待办

- 使各个配方有独立的音效
- `OmniCrafter_Momiji` —— 单配方全能工厂，任意组合输入输出
- `ParallelCrafter_Momiji` —— 并行工厂，同时运行所有可行配方

## 注意事项

- ~~全部~~大部分是用DeepSeek生成的, 新鲜的石, 可能有很多问题, 尽管提, 我能修的都会修的
- 感谢群友 Ls 的库, 主要思路就是这样来的
- json使用时看包里的json示例, java使用时看包里的java示例
- 有事可以可以加qq: 1945542457
- 社恐可以加[ve模组](https://github.com/Martian238/Vanilla-Expansion-Mod)的群: 624367215

## 许可

GPL-3.0 License
就是想用用, 标明原作者就好