"""测试 LLM 偏好抽取：预筛、JSON 解析容错、非法输出收敛"""
import pytest

from core import preference_extractor as pe


@pytest.fixture
def fake_llm(monkeypatch):
    from core.llm import llm_service

    state = {"reply": "", "error": None, "calls": 0}

    def fake_generate(prompt, temperature=0.7, max_tokens=150):
        state["calls"] += 1
        if state["error"]:
            raise state["error"]
        return state["reply"]

    monkeypatch.setattr(llm_service, "generate", fake_generate)
    return state


class TestPrefilter:

    @pytest.mark.parametrize("question,expected", [
        ("以后请用中文回答", True),
        ("统一用中文回答", True),
        ("记住我不喜欢客套话", True),
        ("请叫我老王", True),
        # 口语化表达，早期信号词表漏检过（"后面都给我表格"被判 False）
        ("后面都给我表格", True),
        ("之后回答简短点", True),
        ("接下来别再问我要不要继续", True),
        ("今天天气怎么样", False),
        ("什么是并查集", False),
        ("这段代码怎么优化", False),
    ])
    def test_hints(self, question, expected):
        assert pe.looks_like_preference(question) is expected

    def test_no_hint_skips_llm(self, fake_llm):
        """没有信号词就不要浪费一次模型调用"""
        fake_llm["reply"] = '{"preferences": [{"key": "preference", "value": "x"}]}'

        assert pe.extract_preferences("什么是并查集") == []
        assert fake_llm["calls"] == 0


class TestParsing:

    def test_parses_plain_json(self, fake_llm):
        fake_llm["reply"] = (
            '{"preferences": [{"key": "answer_language", "value": "中文", "confidence": 0.95}]}'
        )

        items = pe.extract_preferences("统一用中文回答")

        assert items == [{
            "key": "answer_language", "value": "中文",
            "confidence": 0.95, "source": "inferred",
        }]

    def test_parses_fenced_json(self, fake_llm):
        """模型很爱套一层 ```json 围栏"""
        fake_llm["reply"] = (
            '```json\n{"preferences": [{"key": "nickname", "value": "老王", "confidence": 0.9}]}\n```'
        )

        items = pe.extract_preferences("以后叫我老王")

        assert items[0]["key"] == "nickname"
        assert items[0]["value"] == "老王"

    def test_empty_preferences(self, fake_llm):
        fake_llm["reply"] = '{"preferences": []}'

        assert pe.extract_preferences("以后再说吧") == []

    def test_invalid_json_returns_empty(self, fake_llm):
        """解析不了就交给调用方降级到正则，不能抛"""
        fake_llm["reply"] = "用户希望以后用中文回答。"

        assert pe.extract_preferences("以后请用中文回答") == []

    def test_llm_error_returns_empty(self, fake_llm):
        fake_llm["error"] = RuntimeError("model timeout")

        assert pe.extract_preferences("以后请用中文回答") == []


class TestNormalization:

    def test_drops_unknown_key(self, fake_llm):
        """模型自造字段会污染 user_memory，必须丢弃"""
        fake_llm["reply"] = (
            '{"preferences": ['
            '{"key": "favorite_color", "value": "蓝色", "confidence": 0.9},'
            '{"key": "answer_language", "value": "中文", "confidence": 0.9}'
            ']}'
        )

        items = pe.extract_preferences("以后请用中文回答")

        assert [i["key"] for i in items] == ["answer_language"]

    def test_drops_low_confidence(self, fake_llm):
        """低于读侧阈值的记忆本来也不会进 Prompt，没必要写库"""
        fake_llm["reply"] = (
            '{"preferences": [{"key": "preference", "value": "喜欢简短", "confidence": 0.3}]}'
        )

        assert pe.extract_preferences("我喜欢简短") == []

    def test_truncates_long_value(self, fake_llm):
        fake_llm["reply"] = (
            '{"preferences": [{"key": "preference", "value": "' + "很" * 100 + '", "confidence": 0.9}]}'
        )

        items = pe.extract_preferences("以后都要这样")

        assert len(items[0]["value"]) == pe._MAX_VALUE_CHARS

    def test_clamps_confidence(self, fake_llm):
        fake_llm["reply"] = (
            '{"preferences": [{"key": "preference", "value": "简洁", "confidence": 5}]}'
        )

        assert pe.extract_preferences("以后都简洁点")[0]["confidence"] == 1.0

    def test_bad_confidence_falls_back(self, fake_llm):
        fake_llm["reply"] = (
            '{"preferences": [{"key": "preference", "value": "简洁", "confidence": "高"}]}'
        )

        assert pe.extract_preferences("以后都简洁点")[0]["confidence"] == 0.85
