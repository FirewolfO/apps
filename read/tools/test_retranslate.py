import io
import json
import unittest
import urllib.error
from unittest import mock

from tools.retranslate import RemoteRefineTranslator, issue_score, quality_issues


class FakeEntities:
    def __init__(self, terms):
        self.known = set(terms)
        self.rare_name_characters = set()

    def terms(self, text):
        return [term for term in self.known if term in text]


class QualityIssuesTest(unittest.TestCase):
    def test_detects_polarity_reversal(self):
        issues = quality_issues("赦栎阳囚死罪以下。", "在栎阳处死了囚犯。", FakeEntities([]))
        self.assertIn("lost-polarity:赦", issues)

    def test_detects_lost_negation(self):
        issues = quality_issues("未见其福。", "已经看见了好处。", FakeEntities([]))
        self.assertIn("lost-negation:1", issues)

    def test_accepts_expanded_double_negation(self):
        issues = quality_issues("天下莫不仰德。", "天下都仰慕他的德行。", FakeEntities([]))
        self.assertFalse(any(issue.startswith("lost-negation:") for issue in issues))

    def test_detects_introduced_entity(self):
        entities = FakeEntities(["塞王欣", "陈胜"])
        issues = quality_issues("枭故塞王欣头。", "处斩了陈胜。", entities)
        self.assertIn("missing-entity:塞王欣", issues)
        self.assertIn("introduced-entity:陈胜", issues)

    def test_fact_preservation_has_high_weight(self):
        self.assertEqual(6, issue_score(["lost-polarity:赦"]))


class RemoteRefineTranslatorTest(unittest.TestCase):
    def test_falls_back_when_chat_output_parser_rejects_valid_text(self):
        error_body = json.dumps(
            {"error": {"message": "output does not match the expected peg-native format"}}
        ).encode()
        chat_error = urllib.error.HTTPError(
            "http://localhost/v1/chat/completions", 500, "error", None, io.BytesIO(error_body)
        )
        completion = io.BytesIO(json.dumps({"choices": [{"text": "译文"}]}).encode())
        translator = RemoteRefineTranslator("http://localhost")

        with mock.patch(
            "tools.retranslate.urllib.request.urlopen",
            side_effect=[chat_error, completion],
        ) as urlopen:
            self.assertEqual("译文", translator.translate_one("原文"))

        self.assertEqual(2, urlopen.call_count)
        self.assertEqual(
            "http://localhost/v1/completions", urlopen.call_args_list[1].args[0].full_url
        )

    def test_round_robins_across_multiple_servers(self):
        response = lambda value: io.BytesIO(
            json.dumps({"choices": [{"message": {"content": value}}]}).encode()
        )
        translator = RemoteRefineTranslator(
            ["http://first", "http://second"]
        )

        with mock.patch(
            "tools.retranslate.urllib.request.urlopen",
            side_effect=[response("译文一"), response("译文二")],
        ) as urlopen:
            self.assertEqual("译文一", translator.translate_one("原文一"))
            self.assertEqual("译文二", translator.translate_one("原文二"))

        self.assertEqual(
            [
                "http://first/v1/chat/completions",
                "http://second/v1/chat/completions",
            ],
            [call.args[0].full_url for call in urlopen.call_args_list],
        )

    def test_uses_legacy_completion_when_content_parser_rejects_output(self):
        parser_error_body = json.dumps(
            {"error": {"message": "output does not match the expected peg-native format"}}
        ).encode()
        content_error_body = json.dumps(
            {"error": {"message": "output does not match the expected Content-only format"}}
        ).encode()
        chat_error = urllib.error.HTTPError(
            "http://localhost/v1/chat/completions",
            500,
            "error",
            None,
            io.BytesIO(parser_error_body),
        )
        completion_error = urllib.error.HTTPError(
            "http://localhost/v1/completions",
            500,
            "error",
            None,
            io.BytesIO(content_error_body),
        )
        legacy_response = io.BytesIO(json.dumps({"content": "译文"}).encode())
        translator = RemoteRefineTranslator("http://localhost")

        with mock.patch(
            "tools.retranslate.urllib.request.urlopen",
            side_effect=[chat_error, completion_error, legacy_response],
        ) as urlopen:
            self.assertEqual("译文", translator.translate_one("原文"))

        self.assertEqual(
            "http://localhost/completion", urlopen.call_args_list[2].args[0].full_url
        )

    def test_protects_and_restores_supplementary_characters(self):
        character = "𣔌"
        marker = "[[U0002350C]]"
        response = io.BytesIO(
            json.dumps(
                {"choices": [{"message": {"content": f"拱{marker}袭封。"}}]}
            ).encode()
        )
        translator = RemoteRefineTranslator("http://localhost")

        with mock.patch(
            "tools.retranslate.urllib.request.urlopen", return_value=response
        ) as urlopen:
            self.assertEqual(f"拱{character}袭封。", translator.translate_one(f"拱{character}袭封。"))

        request = json.loads(urlopen.call_args.args[0].data)
        self.assertEqual(f"拱{marker}袭封。", request["messages"][1]["content"])


if __name__ == "__main__":
    unittest.main()
