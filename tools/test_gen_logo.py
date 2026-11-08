import os
import tempfile
import unittest

from PIL import Image

from gen_logo import generate_gif


class GenerateGifTest(unittest.TestCase):
    def test_creates_looping_animation_from_source(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "logo.png")
            output = os.path.join(directory, "logo.gif")
            Image.new("RGB", (64, 64), (30, 40, 50)).save(source)

            self.assertEqual(generate_gif(source, output), output)
            with Image.open(output) as animation:
                self.assertEqual(animation.size, (64, 64))
                self.assertGreater(animation.n_frames, 1)
                self.assertEqual(animation.info["loop"], 0)
                first = animation.convert("RGB").copy()
                animation.seek(animation.n_frames // 2)
                self.assertNotEqual(first.tobytes(), animation.convert("RGB").tobytes())

    def test_missing_source_raises(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                generate_gif(os.path.join(directory, "missing.png"), os.path.join(directory, "out.gif"))


if __name__ == "__main__":
    unittest.main()
