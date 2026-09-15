#!/usr/bin/env python3
"""Generate the original S01E01 learning demo with a local Piper voice."""

import argparse
import os
import subprocess
import tempfile
import wave
from pathlib import Path


LINES = [
    ("Maya", "Good morning, Leo. Are you ready for the speaking club?", "早上好，Leo。你准备好参加口语小组了吗？"),
    ("Leo", "Almost. I practiced my introduction three times on the bus.", "差不多了。我在公交车上练了三遍自我介绍。"),
    ("Maya", "That sounds prepared, not nervous.", "这听起来是准备充分，不是紧张。"),
    ("Leo", "I still mix up comfortable and convenient.", "我还是会混淆 comfortable 和 convenient。"),
    ("Maya", "Try this: the chair is comfortable, and the train is convenient.", "试试这句：椅子坐着舒服，坐火车很方便。"),
    ("Leo", "So my chair feels good, and the train saves me time.", "也就是说，椅子让我感觉舒服，火车帮我节省时间。"),
    ("Maya", "Exactly. Say it once more, but a little more slowly.", "完全正确。再说一次，不过稍微慢一点。"),
    ("Leo", "The chair is comfortable, and the train is convenient.", "椅子坐着舒服，坐火车很方便。"),
    ("Maya", "Perfect. What should we practice next?", "很好。接下来我们应该练什么？"),
    ("Leo", "Ordering coffee. Yesterday I asked for a large latte and got three.", "练习点咖啡。昨天我要了一大杯拿铁，结果拿到了三杯。"),
    ("Maya", "Three lattes? That's an expensive pronunciation lesson.", "三杯拿铁？这堂发音课可真贵。"),
    ("Leo", "True, but everyone in the office was suddenly very friendly.", "确实，不过办公室里的每个人突然都变得很友好。"),
    ("Maya", "Then let's practice before you buy breakfast for the whole building.", "那就在你请整栋楼吃早餐之前先练习一下吧。"),
    ("Leo", "Deal. One small coffee, please. Just one.", "说定了。请给我一小杯咖啡。只要一杯。"),
]


def timestamp(milliseconds: int) -> str:
    hours, remaining = divmod(milliseconds, 3_600_000)
    minutes, remaining = divmod(remaining, 60_000)
    seconds, millis = divmod(remaining, 1_000)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d},{millis:03d}"


def read_pcm(path: Path):
    with wave.open(str(path), "rb") as source:
        params = source.getparams()
        frames = source.readframes(source.getnframes())
    if params.nchannels != 1 or params.sampwidth != 2 or params.framerate != 22_050:
        raise RuntimeError(f"Unexpected WAV format: {params}")
    return frames


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--output-dir", default="app/src/main/res/raw", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    sample_rate = 22_050
    intro_frames = int(sample_rate * 0.8)
    gap_frames = int(sample_rate * 0.65)
    outro_frames = int(sample_rate * 0.8)
    silence_intro = b"\0\0" * intro_frames
    silence_gap = b"\0\0" * gap_frames
    combined = bytearray(silence_intro)
    cues = []

    with tempfile.TemporaryDirectory(prefix="friends-demo-") as temporary:
        directory = Path(temporary)
        for index, (speaker, english, chinese) in enumerate(LINES, 1):
            original = directory / f"line-{index:02d}-original.wav"
            rendered = directory / f"line-{index:02d}.wav"
            subprocess.run(
                [os.sys.executable, "-m", "piper", "--model", str(args.model), "--output-file", str(original)],
                input=english + "\n",
                text=True,
                check=True,
                stdout=subprocess.DEVNULL,
            )
            filters = "volume=1.05" if speaker == "Maya" else (
                "asetrate=20286,aresample=22050,atempo=1.08696,volume=1.08"
            )
            subprocess.run(
                [args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(original),
                 "-af", filters, "-ar", "22050", "-ac", "1", "-c:a", "pcm_s16le", str(rendered)],
                check=True,
            )
            frames = read_pcm(rendered)
            start_ms = round(len(combined) / 2 * 1000 / sample_rate)
            combined.extend(frames)
            end_ms = round(len(combined) / 2 * 1000 / sample_rate)
            cues.append((start_ms, end_ms, speaker, english, chinese))
            combined.extend(silence_gap)

    combined.extend(b"\0\0" * outro_frames)
    audio_path = args.output_dir / "demo_s01e01.wav"
    with wave.open(str(audio_path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        output.writeframes(combined)

    subtitle_path = args.output_dir / "demo_s01e01_subtitle.srt"
    subtitle_path.write_text(
        "\n\n".join(
            f"{index}\n{timestamp(start)} --> {timestamp(end)}\n{speaker}: {english}\n{chinese}"
            for index, (start, end, speaker, english, chinese) in enumerate(cues, 1)
        ) + "\n",
        encoding="utf-8",
    )
    print(f"Generated {audio_path} and {subtitle_path}")


if __name__ == "__main__":
    main()
