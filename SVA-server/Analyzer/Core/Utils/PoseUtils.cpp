#include "PoseUtils.h"

#include "TrackMetadata.h"

#include <cmath>

namespace SVAAnalyzer
{
    namespace
    {
        /** 关键点可见性门槛:低于该置信度视为不可用(参照方案书 A3)。 */
        constexpr float kMinKeypointConfidence = 0.3f;

        bool isPointUsable(const PoseKeypoint &point)
        {
            return point.confidence >= kMinKeypointConfidence;
        }
    }

    bool computePosePitchDeg(const std::vector<PoseKeypoint> &keypoints,
                             float &pitchDeg,
                             float &noseShoulderGap,
                             float &visibilityAvg)
    {
        pitchDeg = 0.0f;
        noseShoulderGap = 0.0f;
        visibilityAvg = 0.0f;

        // COCO 17 点索引(0 鼻 / 3 左耳 / 4 右耳 / 5 左肩 / 6 右肩)
        const size_t required = 7;
        if (keypoints.size() < required)
        {
            return false;
        }

        const PoseKeypoint &lShoulder = keypoints[PoseIndex::LeftShoulder];
        const PoseKeypoint &rShoulder = keypoints[PoseIndex::RightShoulder];
        if (!isPointUsable(lShoulder) || !isPointUsable(rShoulder))
        {
            return false;
        }

        // 头点选取:双耳中点 → 单耳 → 鼻(兜底)
        const PoseKeypoint &lEar = keypoints[PoseIndex::LeftEar];
        const PoseKeypoint &rEar = keypoints[PoseIndex::RightEar];
        const PoseKeypoint &nose = keypoints[PoseIndex::Nose];

        bool headResolved = false;
        float headX = 0.0f;
        float headY = 0.0f;
        float visibleSum = lShoulder.confidence + rShoulder.confidence;
        int visibleCount = 2;

        if (isPointUsable(lEar) && isPointUsable(rEar))
        {
            headX = (lEar.x + rEar.x) * 0.5f;
            headY = (lEar.y + rEar.y) * 0.5f;
            visibleSum += lEar.confidence + rEar.confidence;
            visibleCount += 2;
            headResolved = true;
        }
        else if (isPointUsable(lEar) || isPointUsable(rEar))
        {
            const PoseKeypoint &ear = isPointUsable(lEar) ? lEar : rEar;
            headX = ear.x;
            headY = ear.y;
            visibleSum += ear.confidence;
            ++visibleCount;
            headResolved = true;
        }
        else if (isPointUsable(nose))
        {
            headX = nose.x;
            headY = nose.y;
            visibleSum += nose.confidence;
            ++visibleCount;
            headResolved = true;
        }

        if (!headResolved)
        {
            return false;
        }

        visibilityAvg = visibleSum / static_cast<float>(visibleCount);

        // v = 肩中点 − 头点(图像坐标,y 向下);与竖直向下轴 (0,1) 的夹角即俯角
        const float midSx = (lShoulder.x + rShoulder.x) * 0.5f;
        const float midSy = (lShoulder.y + rShoulder.y) * 0.5f;
        const float vx = midSx - headX;
        const float vy = midSy - headY;
        const float magnitude = std::sqrt(vx * vx + vy * vy);
        if (magnitude < 1e-3f)
        {
            return false;
        }

        // 头点与肩中点重合或过近:姿态不可判
        float cosAngle = vy / magnitude;
        if (cosAngle > 1.0f)
        {
            cosAngle = 1.0f;
        }
        else if (cosAngle < -1.0f)
        {
            cosAngle = -1.0f;
        }
        pitchDeg = std::acos(cosAngle) * 180.0f / static_cast<float>(M_PI);

        // 调试指标:水平前探比 |dx|/|v|(0=正坐,1=完全前倾趴下;水平转头会增大,仅供调参对照)
        noseShoulderGap = std::fabs(vx) / magnitude;

        return true;
    }
}
