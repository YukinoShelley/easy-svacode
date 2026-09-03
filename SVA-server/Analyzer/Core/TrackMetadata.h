#ifndef ANALYZER_TRACKMETADATA_H
#define ANALYZER_TRACKMETADATA_H

#include <cstdint>
#include <string>
#include <unordered_map>

namespace SVAAnalyzer
{
    /**
     * @brief 人体关键点(图像像素坐标)。
     * 睡岗检测增量:sleep-post(YOLO-Pose)解码器产出,COCO 17 点之一。
     */
    struct PoseKeypoint
    {
        float x = 0.0f;
        float y = 0.0f;
        float confidence = 0.0f;
    };

    /**
     * @brief 一个时间点的头部俯角采样(时序判定用)。
     * 睡岗检测增量:记录于 TemporalTrackState / DetectObject.posePitchHistory。
     */
    struct PosePitchSample
    {
        int64_t timestampMs = 0;
        float pitchDeg = 0.0f;
    };

    struct TrackTrailPoint
    {
        int x = 0;
        int y = 0;
        float speedPxPerSec = 0.0f;
        int64_t timestampMs = 0;
    };

    struct RegionTemporalState
    {
        bool inRegion = false;
        bool enteredRegion = false;
        bool exitedRegion = false;
        int64_t inRegionDurationMs = 0;
        int64_t lastEnterTimestampMs = 0;
        int64_t lastLeaveTimestampMs = 0;
    };
}

#endif // ANALYZER_TRACKMETADATA_H