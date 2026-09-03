#include "TemporalContext.h"
#include "Algorithm.h"
#include "Control.h"
#include "Utils/GeometryUtils.h"
#include "Utils/PoseUtils.h" // 睡岗增量:平滑后关键点 → 俯角

#include <algorithm>
#include <cmath>
#include <limits>
#include <unordered_set>

namespace SVAAnalyzer
{
    namespace
    {
        static constexpr float kMinIou = 0.30f;
        static constexpr int kMaxMissedFrames = 30;
        static constexpr int64_t kMaxMissedMs = 4000;
        static constexpr size_t kMaxTrailPoints = 32;
        static constexpr float kStillSpeedThreshold = 12.0f;
        // ===== 睡岗增量 (sleep-post) =====
        static constexpr size_t kMaxPosePitchSamples = 64; // 俯角历史上限(条)
        static constexpr int64_t kMaxPosePitchWindowMs = 10000; // 俯角历史窗口(ms)
        static constexpr float kPoseKeypointEmaAlpha = 0.6f; // 关键点 EMA 平滑系数(与 box EMA 0.7 同风格)

        /**
         * @brief Simple IoU calculation between a detection box and a track box.
         * Teaching note: IoU (Intersection over Union) measures how much two boxes overlap.
         * Higher IoU = better match. Used in greedy matching below.
         */
        static float calcIoU(const DetectObject &detect, const TemporalTrackState &track)
        {
            const int interLeft = std::max(detect.x1, track.x1);
            const int interTop = std::max(detect.y1, track.y1);
            const int interRight = std::min(detect.x2, track.x2);
            const int interBottom = std::min(detect.y2, track.y2);
            const int interWidth = std::max(0, interRight - interLeft);
            const int interHeight = std::max(0, interBottom - interTop);
            const int interArea = interWidth * interHeight;
            if (interArea <= 0)
            {
                return 0.0f;
            }

            const int detectArea = std::max(0, detect.x2 - detect.x1) * std::max(0, detect.y2 - detect.y1);
            const int trackArea = std::max(0, track.x2 - track.x1) * std::max(0, track.y2 - track.y1);
            const int unionArea = detectArea + trackArea - interArea;
            if (unionArea <= 0)
            {
                return 0.0f;
            }
            return static_cast<float>(interArea) / static_cast<float>(unionArea);
        }

        /**
         * @brief Simple greedy matching: for each detection, find the best-matching track by IoU.
         * Teaching note: This is a simplified approach for learning. Production systems
         * often use Hungarian algorithm or Kalman filter for better matching.
         */
        struct MatchResult
        {
            std::unordered_set<int> matchedTrackIds;
            std::unordered_set<size_t> matchedDetectIndices;
        };

        static MatchResult greedyMatch(const std::unordered_map<int, TemporalTrackState> &tracks,
                                       const std::vector<DetectObject *> &detects,
                                       float minIou)
        {
            MatchResult result;

            // For each detection, find the best matching track (if any)
            for (size_t di = 0; di < detects.size(); ++di)
            {
                DetectObject *detect = detects[di];
                if (!detect || result.matchedDetectIndices.count(di))
                {
                    continue;
                }

                float bestIou = minIou;
                int bestTrackId = -1;

                for (const auto &entry : tracks)
                {
                    const int trackId = entry.first;
                    const TemporalTrackState &track = entry.second;
                    if (track.lifeState == TemporalTrackLifecycleState::Removed)
                    {
                        continue;
                    }
                    if (result.matchedTrackIds.count(trackId))
                    {
                        continue;
                    }

                    // Only match same class / same algorithm
                    if (detect->class_id >= 0 && track.classId >= 0 && detect->class_id != track.classId)
                    {
                        continue;
                    }
                    if (!detect->source_algorithm.empty() && !track.algorithmCode.empty() &&
                        detect->source_algorithm != track.algorithmCode)
                    {
                        continue;
                    }

                    float iou = calcIoU(*detect, track);
                    if (iou > bestIou)
                    {
                        bestIou = iou;
                        bestTrackId = trackId;
                    }
                }

                if (bestTrackId >= 0)
                {
                    result.matchedTrackIds.insert(bestTrackId);
                    result.matchedDetectIndices.insert(di);
                }
            }

            return result;
        }

        /**
         * @brief Append a trail point for trajectory visualization and analysis.
         */
        static void appendTrail(TemporalTrackState &track, int x1, int y1, int x2, int y2, int64_t timestampMs)
        {
            TrackTrailPoint point;
            point.x = (x1 + x2) / 2;
            point.y = (y1 + y2) / 2;
            point.timestampMs = timestampMs;
            track.trail.push_back(point);
            while (track.trail.size() > kMaxTrailPoints)
            {
                track.trail.pop_front();
            }
        }

        static void updateRegionStates(TemporalTrackState &track, const Control &control, int64_t timestampMs)
        {
            const GeometryPoint center = calcBoxCenter(track.x1, track.y1, track.x2, track.y2);
            for (size_t i = 0; i < control.regions.size(); ++i)
            {
                const RegionConfig &region = control.regions[i];
                if (region.id.empty() || region.polygon_d.size() < 6)
                {
                    continue;
                }

                RegionTemporalState &state = track.regionStates[region.id];
                const bool wasInRegion = state.inRegion;
                const bool inRegion = isPointInPolygon(center, buildPolygonPoints(region.polygon_d));
                state.enteredRegion = false;
                state.exitedRegion = false;

                if (inRegion && !wasInRegion)
                {
                    state.enteredRegion = true;
                    state.lastEnterTimestampMs = timestampMs;
                }
                else if (!inRegion && wasInRegion)
                {
                    state.exitedRegion = true;
                    state.lastLeaveTimestampMs = timestampMs;
                    state.inRegionDurationMs = 0;
                }

                state.inRegion = inRegion;
                if (state.inRegion)
                {
                    if (state.lastEnterTimestampMs <= 0)
                    {
                        state.lastEnterTimestampMs = timestampMs;
                    }
                    state.inRegionDurationMs = std::max<int64_t>(0, timestampMs - state.lastEnterTimestampMs);
                }
            }
        }

        /**
         * @brief 睡岗增量:更新 track 的姿态状态(EMA 平滑关键点 → 重算俯角 → 采样入历史)。
         *
         * 仅在 detect 关键点有效时推进;某帧姿态不可用(遮挡/可见性不足)时不采样,
         * 俯角历史自然出现"缺口"→ BehaviorEvaluator 的连续段判定自动中断,不会误累计低头时长。
         */
        static void updatePoseTemporalState(TemporalTrackState &track, const DetectObject &detect, int64_t timestampMs)
        {
            if (!detect.keypointsPresent || detect.keypoints.size() < 7)
            {
                // 清空平滑状态,避免用陈旧几何与下一帧做 EMA 混合;历史保留(缺口语义)
                track.keypoints.clear();
                track.keypointsPresent = false;
                track.posePitchDeg = 0.0f;
                return;
            }

            const size_t count = detect.keypoints.size();
            if (track.keypoints.size() == count && track.keypointsPresent)
            {
                // 已有平滑状态 → EMA(坐标平滑,置信度取最新帧)
                for (size_t i = 0; i < count; ++i)
                {
                    track.keypoints[i].x = kPoseKeypointEmaAlpha * detect.keypoints[i].x +
                                           (1.0f - kPoseKeypointEmaAlpha) * track.keypoints[i].x;
                    track.keypoints[i].y = kPoseKeypointEmaAlpha * detect.keypoints[i].y +
                                           (1.0f - kPoseKeypointEmaAlpha) * track.keypoints[i].y;
                    track.keypoints[i].confidence = detect.keypoints[i].confidence;
                }
            }
            else
            {
                // 首帧/中断后重新可见 → 直接采用原始关键点
                track.keypoints = detect.keypoints;
            }

            float pitchDeg = 0.0f;
            float noseShoulderGap = 0.0f;
            float visibilityAvg = 0.0f;
            if (!computePosePitchDeg(track.keypoints, pitchDeg, noseShoulderGap, visibilityAvg))
            {
                // 平滑后可见性仍不足(理论上罕见:conf 未被 EMA 稀释)
                track.keypointsPresent = false;
                track.posePitchDeg = 0.0f;
                return;
            }

            track.keypointsPresent = true;
            track.posePitchDeg = pitchDeg;

            PosePitchSample sample;
            sample.timestampMs = timestampMs;
            sample.pitchDeg = pitchDeg;
            track.posePitchHistory.push_back(sample);

            // 双上限:条数 64 / 时间窗 10s(按时间戳裁剪,低帧率下时长仍精确)
            while (track.posePitchHistory.size() > kMaxPosePitchSamples)
            {
                track.posePitchHistory.pop_front();
            }
            while (!track.posePitchHistory.empty() &&
                   timestampMs - track.posePitchHistory.front().timestampMs > kMaxPosePitchWindowMs)
            {
                track.posePitchHistory.pop_front();
            }
        }

        /**
         * @brief Copy temporal fields from track state to detection object.
         * This enriches detection output with tracking metadata for downstream behavior analysis.
         */
        static void writeTemporalFields(const TemporalTrackState &track, DetectObject &detect,
                                        int64_t /*timestampMs*/, bool trackNew)
        {
            detect.trackId = track.trackId;
            detect.firstSeenTimestampMs = track.firstSeenTimestampMs;
            detect.lastSeenTimestampMs = track.lastSeenTimestampMs;
            detect.dwellMs = std::max<int64_t>(0, track.lastSeenTimestampMs - track.firstSeenTimestampMs);
            detect.trackAgeFrames = track.ageFrames;
            detect.trackMissedFrames = track.missedFrames;
            detect.speedPxPerSec = track.speedPxPerSec;
            detect.velocityXPxPerSec = track.velocityXPxPerSec;
            detect.velocityYPxPerSec = track.velocityYPxPerSec;
            detect.directionAngleDeg = track.directionAngleDeg;
            detect.motionState = track.motionState;
            detect.trackNew = trackNew;
            detect.regionStates = track.regionStates;
            detect.trail.assign(track.trail.begin(), track.trail.end());
            // 睡岗增量:姿态状态回写(与 trail 同构,供 BehaviorEvaluator 读取)
            detect.keypoints = track.keypoints;
            detect.keypointsPresent = track.keypointsPresent;
            detect.posePitchDeg = track.posePitchDeg;
            detect.posePitchHistory.assign(track.posePitchHistory.begin(), track.posePitchHistory.end());
        }

        /**
         * @brief Update a matched track with a new detection.
         */
        static void updateMatchedTrack(TemporalTrackState &track, const Control &control, DetectObject &detect,
                                       int64_t timestampMs, bool trackNew)
        {
            // Calculate center positions for speed/direction computation
            float oldCx = (track.x1 + track.x2) * 0.5f;
            float oldCy = (track.y1 + track.y2) * 0.5f;
            float newCx = (detect.x1 + detect.x2) * 0.5f;
            float newCy = (detect.y1 + detect.y2) * 0.5f;

            int64_t deltaMs = std::max<int64_t>(1, timestampMs - std::max<int64_t>(1, track.lastSeenTimestampMs));
            float dx = newCx - oldCx;
            float dy = newCy - oldCy;
            float distance = std::sqrt(dx * dx + dy * dy);

            // Update bounding box (simple EMA smoothing)
            const float alpha = 0.7f;
            track.x1 = static_cast<int>(alpha * detect.x1 + (1.0f - alpha) * track.x1);
            track.y1 = static_cast<int>(alpha * detect.y1 + (1.0f - alpha) * track.y1);
            track.x2 = static_cast<int>(alpha * detect.x2 + (1.0f - alpha) * track.x2);
            track.y2 = static_cast<int>(alpha * detect.y2 + (1.0f - alpha) * track.y2);
            track.score = detect.class_score;
            track.classId = detect.class_id;
            track.className = detect.class_name;
            if (!detect.source_algorithm.empty())
            {
                track.algorithmCode = detect.source_algorithm;
            }

            // Speed and direction
            track.velocityXPxPerSec = dx * 1000.0f / static_cast<float>(deltaMs);
            track.velocityYPxPerSec = dy * 1000.0f / static_cast<float>(deltaMs);
            track.speedPxPerSec = distance * 1000.0f / static_cast<float>(deltaMs);
            if (distance > 1e-3f)
            {
                track.directionAngleDeg = static_cast<float>(std::atan2(dy, dx) * 180.0 / M_PI);
            }

            // Motion state classification
            track.motionState = track.speedPxPerSec <= kStillSpeedThreshold ? "still" : "moving";
            if (track.motionState == "moving")
            {
                track.lastMovedTimestampMs = timestampMs;
            }

            track.ageFrames += 1;
            track.missedFrames = 0;
            track.consecutiveVisibleFrames += 1;
            track.lastSeenTimestampMs = timestampMs;
            if (track.firstSeenTimestampMs <= 0)
            {
                track.firstSeenTimestampMs = timestampMs;
            }
            track.lifeState = track.consecutiveVisibleFrames >= 2
                                  ? TemporalTrackLifecycleState::Tracked
                                  : TemporalTrackLifecycleState::New;

            appendTrail(track, detect.x1, detect.y1, detect.x2, detect.y2, timestampMs);
            updateRegionStates(track, control, timestampMs);
            updatePoseTemporalState(track, detect, timestampMs); // 睡岗增量
            writeTemporalFields(track, detect, timestampMs, trackNew);
        }

        /**
         * @brief Create a new track from a fresh detection.
         */
        static TemporalTrackState createTrack(StreamTemporalContext &context,
                              const Control &control,
                                              DetectObject &detect, int64_t timestampMs)
        {
            TemporalTrackState track;
            track.trackId = context.nextTrackId++;
            track.x1 = detect.x1;
            track.y1 = detect.y1;
            track.x2 = detect.x2;
            track.y2 = detect.y2;
            track.score = detect.class_score;
            track.classId = detect.class_id;
            track.className = detect.class_name;
            track.algorithmCode = detect.source_algorithm;
            track.firstSeenTimestampMs = timestampMs;
            track.lastSeenTimestampMs = timestampMs;
            track.lastMovedTimestampMs = timestampMs;
            track.ageFrames = 1;
            track.missedFrames = 0;
            track.consecutiveVisibleFrames = 1;
            track.speedPxPerSec = 0.0f;
            track.velocityXPxPerSec = 0.0f;
            track.velocityYPxPerSec = 0.0f;
            track.directionAngleDeg = 0.0f;
            track.motionState = "new";
            track.lifeState = TemporalTrackLifecycleState::New;

            appendTrail(track, detect.x1, detect.y1, detect.x2, detect.y2, timestampMs);
            updateRegionStates(track, control, timestampMs);
            // 睡岗增量:新 track 直接采用本帧姿态并记录首条采样
            track.keypoints = detect.keypoints;
            track.keypointsPresent = detect.keypointsPresent;
            track.posePitchDeg = detect.posePitchDeg;
            if (track.keypointsPresent && track.posePitchDeg > 0.0f)
            {
                PosePitchSample sample;
                sample.timestampMs = timestampMs;
                sample.pitchDeg = track.posePitchDeg;
                track.posePitchHistory.push_back(sample);
            }
            writeTemporalFields(track, detect, timestampMs, true);
            return track;
        }

        static void markTrackLostOrRemoved(TemporalTrackState &track, int64_t timestampMs)
        {
            track.missedFrames += 1;
            const int64_t lostMs = timestampMs - track.lastSeenTimestampMs;
            if (track.missedFrames > kMaxMissedFrames || lostMs > kMaxMissedMs)
            {
                track.lifeState = TemporalTrackLifecycleState::Removed;
                return;
            }
            track.lifeState = TemporalTrackLifecycleState::Lost;
            track.motionState = "lost";
        }
    }

    /**
     * @brief Main entry point: update temporal tracking state for a stream.
     *
     * This function:
     * 1. Matches new detections to existing tracks (greedy IoU matching)
     * 2. Creates new tracks for unmatched detections
     * 3. Marks unmatched tracks as lost/removed
     * 4. Cleans up removed tracks
     *
     * Teaching note: For worker-based architecture, this is called once per frame
     * from the Worker's decode thread, before behavior evaluation.
     */
    void TemporalProcessor::updateStream(StreamTemporalContext &context,
                                         const Control &control,
                                         std::vector<DetectObject *> &detects,
                                         int64_t timestampMs)
    {
        context.lastFrameTimestampMs = timestampMs;

        // Step 1: Greedy IoU matching between detections and existing tracks
        MatchResult matchResult = greedyMatch(context.activeTracks, detects, kMinIou);

        // Step 2: Update matched tracks, create new ones for unmatched detections
        std::unordered_set<int> matchedTrackIds;
        for (auto &entry : context.activeTracks)
        {
            TemporalTrackState &track = entry.second;
            if (track.lifeState == TemporalTrackLifecycleState::Removed)
            {
                continue;
            }

            if (matchResult.matchedTrackIds.count(entry.first))
            {
                // Find which detection matched this track (reverse lookup)
                for (size_t di = 0; di < detects.size(); ++di)
                {
                    DetectObject *detect = detects[di];
                    if (!detect || !matchResult.matchedDetectIndices.count(di))
                    {
                        continue;
                    }
                    // Simple check: does this detection overlap with this track?
                    if (calcIoU(*detect, track) >= kMinIou)
                    {
                        bool trackNew = (track.lifeState == TemporalTrackLifecycleState::New);
                        updateMatchedTrack(track, control, *detect, timestampMs, trackNew);
                        matchedTrackIds.insert(entry.first);
                        break;
                    }
                }
            }
        }

        // Create new tracks for unmatched detections
        for (size_t di = 0; di < detects.size(); ++di)
        {
            DetectObject *detect = detects[di];
            if (!detect || matchResult.matchedDetectIndices.count(di))
            {
                continue;
            }

            TemporalTrackState track = createTrack(context, control, *detect, timestampMs);
            matchedTrackIds.insert(track.trackId);
            context.activeTracks[track.trackId] = std::move(track);
        }

        // Step 3: Mark unmatched tracks as lost/removed
        for (auto &entry : context.activeTracks)
        {
            if (entry.second.lifeState == TemporalTrackLifecycleState::Removed)
            {
                continue;
            }
            if (matchedTrackIds.find(entry.first) == matchedTrackIds.end())
            {
                markTrackLostOrRemoved(entry.second, timestampMs);
            }
        }

        // Step 4: Clean up removed tracks
        for (auto it = context.activeTracks.begin(); it != context.activeTracks.end();)
        {
            if (it->second.lifeState == TemporalTrackLifecycleState::Removed)
            {
                it = context.activeTracks.erase(it);
            }
            else
            {
                ++it;
            }
        }
    }
}