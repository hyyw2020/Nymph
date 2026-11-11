package dev.nymph.auto.core.vision

/**
 * COCO 80 类标签 + 中文别名（AI 找目标的"文字描述 → 检测类别"映射）。
 *
 * 纯 JVM 无依赖：映射表就是普通数据，可被单元测试全覆盖，
 * 也方便使用者按需扩充别名（改这一张表即可，无魔法）。
 */
object CocoLabels {

    /** COCO 80 类的英文标签（efficientdet-lite0 metadata 标签序）。 */
    val labels: List<String> = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
        "toothbrush",
    )

    /** 中文别名 → COCO 英文标签。按"最长匹配优先"消除歧义（如"汽车"优先于"车"）。 */
    private val zhAliases: Map<String, String> = mapOf(
        "人" to "person", "人物" to "person", "人群" to "person",
        "自行车" to "bicycle", "单车" to "bicycle", "脚踏车" to "bicycle",
        "汽车" to "car", "轿车" to "car", "小车" to "car",
        "摩托车" to "motorcycle", "摩托" to "motorcycle", "电动车" to "motorcycle",
        "飞机" to "airplane",
        "公交车" to "bus", "巴士" to "bus", "公共汽车" to "bus",
        "火车" to "train", "列车" to "train",
        "卡车" to "truck", "货车" to "truck", "卡车头" to "truck",
        "船" to "boat", "小船" to "boat",
        "红绿灯" to "traffic light", "交通灯" to "traffic light", "信号灯" to "traffic light",
        "消防栓" to "fire hydrant",
        "停止标志" to "stop sign", "停车标志" to "stop sign", "停止牌" to "stop sign",
        "停车计时器" to "parking meter", "停车桩" to "parking meter",
        "长椅" to "bench", "长凳" to "bench",
        "鸟" to "bird", "小鸟" to "bird",
        "猫" to "cat", "猫咪" to "cat", "小猫" to "cat",
        "狗" to "dog", "狗狗" to "dog", "小狗" to "dog",
        "马" to "horse", "马匹" to "horse",
        "羊" to "sheep", "绵羊" to "sheep",
        "牛" to "cow", "奶牛" to "cow",
        "大象" to "elephant",
        "熊" to "bear",
        "斑马" to "zebra",
        "长颈鹿" to "giraffe",
        "背包" to "backpack", "双肩包" to "backpack", "书包" to "backpack",
        "雨伞" to "umbrella", "伞" to "umbrella",
        "手提包" to "handbag", "手袋" to "handbag", "挎包" to "handbag",
        "领带" to "tie",
        "手提箱" to "suitcase", "行李箱" to "suitcase", "箱子" to "suitcase",
        "飞盘" to "frisbee",
        "滑雪板" to "skis", "雪橇" to "skis",
        "单板滑雪" to "snowboard",
        "运动球" to "sports ball", "球" to "sports ball",
        "风筝" to "kite",
        "棒球棒" to "baseball bat", "球棒" to "baseball bat",
        "棒球手套" to "baseball glove", "手套" to "baseball glove",
        "滑板" to "skateboard",
        "冲浪板" to "surfboard",
        "网球拍" to "tennis racket", "球拍" to "tennis racket",
        "瓶子" to "bottle", "水瓶" to "bottle", "饮料瓶" to "bottle",
        "酒杯" to "wine glass", "高脚杯" to "wine glass",
        "杯子" to "cup", "水杯" to "cup", "茶杯" to "cup",
        "叉子" to "fork",
        "刀" to "knife", "水果刀" to "knife",
        "勺子" to "spoon", "汤匙" to "spoon",
        "碗" to "bowl",
        "香蕉" to "banana",
        "苹果" to "apple",
        "三明治" to "sandwich",
        "橙子" to "orange", "橘子" to "orange",
        "西兰花" to "broccoli", "花椰菜" to "broccoli",
        "胡萝卜" to "carrot",
        "热狗" to "hot dog",
        "披萨" to "pizza", "比萨" to "pizza",
        "甜甜圈" to "donut",
        "蛋糕" to "cake",
        "椅子" to "chair", "凳子" to "chair",
        "沙发" to "couch",
        "盆栽" to "potted plant", "植物" to "potted plant", "绿植" to "potted plant",
        "床" to "bed",
        "餐桌" to "dining table", "桌子" to "dining table", "饭桌" to "dining table",
        "厕所" to "toilet", "马桶" to "toilet", "洗手间" to "toilet",
        "电视" to "tv", "电视机" to "tv",
        "笔记本电脑" to "laptop", "笔记本" to "laptop", "电脑" to "laptop",
        "鼠标" to "mouse",
        "遥控器" to "remote",
        "键盘" to "keyboard",
        "手机" to "cell phone", "电话" to "cell phone", "移动电话" to "cell phone",
        "微波炉" to "microwave",
        "烤箱" to "oven",
        "烤面包机" to "toaster",
        "水槽" to "sink", "洗手池" to "sink",
        "冰箱" to "refrigerator",
        "书" to "book", "书本" to "book",
        "时钟" to "clock", "钟表" to "clock", "挂钟" to "clock",
        "花瓶" to "vase",
        "剪刀" to "scissors",
        "泰迪熊" to "teddy bear", "玩具熊" to "teddy bear", "毛绒玩具" to "teddy bear",
        "吹风机" to "hair drier", "电吹风" to "hair drier",
        "牙刷" to "toothbrush",
    )

    /**
     * 文字描述 → COCO 英文标签。找不到返回 null（调用方如实归因
     * "描述无法映射到离线模型类别"，而不是悄悄点一个错误的框）。
     *
     * 匹配策略（从严到宽）：
     *  1. 英文标签本身（person / cell phone / traffic light）
     *  2. 中文别名精确相等
     *  3. 中文别名互相包含（最长别名优先，避免"车"抢先于"汽车"）
     */
    fun resolve(description: String): String? {
        val q = description.trim().lowercase()
        if (q.isEmpty()) return null
        labels.firstOrNull { it.lowercase() == q }?.let { return it }
        zhAliases[q]?.let { return it }
        // 互相包含：候选别名至少 2 字，取最长匹配（最具体）
        val byLen = zhAliases.entries
            .filter { (a, _) -> a.length >= 2 && (q.contains(a) || a.contains(q)) }
            .maxByOrNull { (a, _) -> a.length }
        return byLen?.value
    }
}
