package com.example.fitcounter;

final class PoseCrop {
    FrameTransform next(FrameTransform full, FrameTransform previous, Pose pose) {
        int maxDimension = Math.max(full.uprightWidth, full.uprightHeight);
        int side = Math.min(pose.confidence[5], pose.confidence[11])
                >= Math.min(pose.confidence[6], pose.confidence[12]) ? 0 : 1;
        float torso = (float) pose.distance(5 + side, 11 + side);
        float minX = pose.x[5 + side], maxX = minX, minY = pose.y[5 + side], maxY = minY;
        for (int i = 0; i < Pose.SIZE; i++) {
            if (!pose.visible(i, 0.36f) || pose.distance(11 + side, i) > 3 * torso) continue;
            minX = Math.min(minX, pose.x[i]);
            maxX = Math.max(maxX, pose.x[i]);
            minY = Math.min(minY, pose.y[i]);
            maxY = Math.max(maxY, pose.y[i]);
        }
        // Запас по длине тела нужен, чтобы колени и голова оставались в области при переходе вверх.
        float extent = Math.max(maxX - minX, maxY - minY);
        int size = 16 * (int) Math.ceil(Math.max(maxDimension * 0.43f,
                Math.max(torso * 3.4f, extent * 1.27f)) / 16);
        if (previous.cropSize != 0) size = Math.max(size, previous.cropSize - 16);
        if (size >= maxDimension * 0.92f) return full;
        int x = 8 * Math.round((minX + maxX - size) / 16);
        float yCenter = (minY + maxY) * 0.5f - Math.max(0, size - (maxY - minY)) * 0.08f;
        int y = 8 * Math.round((yCenter - size * 0.5f) / 8);
        if (previous.cropSize == size && Math.abs(previous.cropX - x) <= 8
                && Math.abs(previous.cropY - y) <= 8) return previous;
        return new FrameTransform(full.width, full.height, full.rotation, full.inputSize, x, y, size);
    }
}
