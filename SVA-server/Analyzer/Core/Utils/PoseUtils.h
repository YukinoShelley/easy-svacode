#ifndef ANALYZER_POSEUTILS_H
#define ANALYZER_POSEUTILS_H

#include <vector>

namespace SVAAnalyzer
{
    struct PoseKeypoint;

    /**
     * @brief 由 17 个人体关键点(COCO 顺序)计算头部姿态指标(俯角 + 头肩距离比)。
     * 睡岗检测增量(sleep-post)专用姿态几何,纯函数、无状态、与推理引擎解耦。
     *
     * ## 几何定义
     * - 关键点索引:0=鼻 3=左耳 4=右耳 5=左肩 6=右肩(COCO 17 点,ultralytics 官方约定)。
     * - 头点选取(按优先级):双耳中点 → 单耳 → 鼻(前两者不可见时兜底)。
     * - 肩中点 = (左肩+右肩)/2;v = 肩中点 − 头点(图像坐标,y 向下)。
     * - pitchDeg:俯角 = v 与"竖直向下轴"(0,1)的夹角:
     *     0° ≈ 直立抬头;60° ≈ 明显低头;90° ≈ 头与肩齐平(趴桌);>120° ≈ 头低于肩。
     * - headDropRatio(AI 角色 Python 验证口径,θ_hd≈0.12):(肩中点Y − 头点Y)/框高;
     *     直立约 0.2+,低头时变小,0 附近=头与肩齐平(趴桌),负值=头低于肩。
     *
     * ## 有效性门槛
     * 双肩与任一有效头点置信度 < 0.3 视为关键点不可用(返回 false),避免遮挡/抖动误判。
     *
     * @param keypoints 17 个关键点(需 size>=7,只读索引 0/3/4/5/6)
     * @param boxHeight 检测框高度(像素,用于 headDropRatio 归一化;<=0 时 hd 输出 0)
     * @param pitchDeg 输出:俯角[0,180]
     * @param noseShoulderGap 输出:头点相对肩中点的水平前探比 |dx|/|v|(0~1,趴桌≈1);仅调试参考(水平转头会干扰)
     * @param headDropRatio 输出:头肩距离比(仅 boxHeight>0 时有意义)
     * @param visibilityAvg 输出:参与计算的点的平均置信度(调试用)
     * @return false = 关键点不可用(此时不写 pitchDeg/headDropRatio)
     */
    bool computePoseMetrics(const std::vector<PoseKeypoint> &keypoints,
                            float boxHeight,
                            float &pitchDeg,
                            float &noseShoulderGap,
                            float &headDropRatio,
                            float &visibilityAvg);

    /**
     * @brief 兼容封装:仅算俯角/水平前探/可见性(不填 headDropRatio)。
     * 等价于 computePoseMetrics(keypoints, boxHeight<=0, ...)。
     */
    bool computePosePitchDeg(const std::vector<PoseKeypoint> &keypoints,
                             float &pitchDeg,
                             float &noseShoulderGap,
                             float &visibilityAvg);

    /**
     * @brief COCO 17 点中与姿态几何相关的索引常量。
     */
    namespace PoseIndex
    {
        enum
        {
            Nose = 0,
            LeftEar = 3,
            RightEar = 4,
            LeftShoulder = 5,
            RightShoulder = 6,
        };
    }
}

#endif // ANALYZER_POSEUTILS_H
