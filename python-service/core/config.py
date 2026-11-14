import os
import logging
from typing import Dict, Any

class ConfigManager:
    """统一配置管理模块"""
    
    def __init__(self):
        """初始化配置管理器"""
        self._load_config()
        self._setup_logging()
        # 此前 validate_config 定义了却从未被调用, 校验形同虚设;
        # 联网配置的"静默失效"正是靠它在启动期暴露, 否则只能等请求回来才发现
        self.validate_config()
    
    def _load_config(self):
        """加载配置项"""
        # AI Embeddings
        self.DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", "")
        # Embedding模型选择: "dashscope" 使用云端API, "local" 使用本地中文模型
        self.EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "dashscope")
        # 本地Embedding模型名称（仅当EMBEDDING_MODEL=local时生效）
        self.LOCAL_EMBEDDING_MODEL = os.getenv("LOCAL_EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5")

        # LLM（对话模型）Configuration —— 走百炼 OpenAI 兼容端点
        # 同一端点兼容通义 / DeepSeek / Kimi 等托管模型, 换模型只需改 LLM_MODEL, 无需动代码
        # 注意: 百炼托管模型名带版本号, 下架后调用会 403/404(如 deepseek-v4-flash-0731 已下架),
        # 换模型优先看控制台「免费额度」列表里的在线 model code
        self.LLM_MODEL = os.getenv("LLM_MODEL", "deepseek-v4.1-flash")
        self.LLM_BASE_URL = os.getenv(
            "LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
        # 切到其他厂商时可单独配 LLM_API_KEY; 不配则沿用 DASHSCOPE_API_KEY(向后兼容)
        self.LLM_API_KEY = os.getenv("LLM_API_KEY") or self.DASHSCOPE_API_KEY
        
        # Milvus Configuration
        self.MILVUS_HOST = os.getenv("MILVUS_HOST", "localhost")
        self.MILVUS_PORT = os.getenv("MILVUS_PORT", "19530")
        self.MILVUS_USER = os.getenv("MILVUS_USER", "")
        self.MILVUS_PASSWORD = os.getenv("MILVUS_PASSWORD", "")

        # Redis Configuration
        self.REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
        self.REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
        self.REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "")
        self.REDIS_DB = int(os.getenv("REDIS_DB", "0"))

        # 内部接口鉴权（Java -> Python 的 /api/internal/*）。调用方是后端服务、没有用户身份，
        # 因此不复用用户 JWT。未配置时内部接口一律拒绝，同时 Java 侧会自动降级到本地兜底，
        # 不会因为漏配导致摘要功能整体失效。
        self.INTERNAL_TOKEN = os.getenv("INTERNAL_TOKEN", "")

        # MySQL Configuration
        self.DB_HOST = os.getenv("MYSQL_HOST", "localhost")
        self.DB_PORT = int(os.getenv("MYSQL_PORT", "3306"))
        self.DB_USER = os.getenv("MYSQL_USERNAME", "root")
        self.DB_PASSWORD = os.getenv("MYSQL_PASSWORD", "123456")
        self.DB_NAME = os.getenv("MYSQL_DATABASE", "ai_knowledge_db")
        
        # Vector Store Configuration
        self.USE_MILVUS = os.getenv("USE_MILVUS", "true").lower() == "true"
        self.VECTOR_STORE_PERSIST_DIR = os.getenv("VECTOR_STORE_PERSIST_DIR", "./faiss_index")
        self.VECTOR_STORE_COLLECTION_NAME = os.getenv("VECTOR_STORE_COLLECTION_NAME", "ai_knowledge_collection")
        
        # Rerank Configuration
        self.RERANKER_TYPE = os.getenv("RERANKER_TYPE", "simple")
        self.COHERE_API_KEY = os.getenv("COHERE_API_KEY", "")

        # Web Search Configuration (知识库零命中时的联网补救 —— 可选能力)
        # 开关默认 true, 但真正生效还需要 TAVILY_API_KEY; 没配 key 时整条链路自动跳过。
        # 之所以"默认开"而不是"默认关": 不必让使用者记着去改开关, 配了 key 即生效,
        # 而未配置的环境行为与加此功能前完全一致(见 tools/web_search.py 的 is_available)
        self.WEB_SEARCH_ENABLED = os.getenv(
            "WEB_SEARCH_ENABLED", "true").lower() == "true"
        # 联网方式:
        #   bailian —— 给 LLM 传 enable_search, 由百炼服务端检索(默认, 零配置即可用;
        #              代价: 拿不到结构化来源, 且搜索结果会撑大 prompt_tokens)
        #   tool    —— 调用本项目 WebSearchTool(需配搜索服务 key, 能拿到来源链接)
        #   off     —— 不联网
        self.WEB_SEARCH_MODE = os.getenv("WEB_SEARCH_MODE", "bailian")
        self.WEB_SEARCH_PROVIDER = os.getenv("WEB_SEARCH_PROVIDER", "tavily")
        self.TAVILY_API_KEY = os.getenv("TAVILY_API_KEY", "")
        # 8s 上限: 联网是"补救手段", 宁可放弃也不能拖慢主链路
        self.WEB_SEARCH_TIMEOUT_MS = int(os.getenv("WEB_SEARCH_TIMEOUT_MS", "8000"))
        self.WEB_SEARCH_TOP_K = int(os.getenv("WEB_SEARCH_TOP_K", "5"))

        # Faithfulness Judge Configuration (LLM-as-judge 内容忠实度评估 —— 评估层第 3 层)
        # 默认开启 + 全量采样(本地开发便于观察); 生产可调低采样率控制 token 成本
        self.FAITHFULNESS_JUDGE_ENABLED = os.getenv(
            "FAITHFULNESS_JUDGE_ENABLED", "true").lower() == "true"
        self.FAITHFULNESS_JUDGE_SAMPLE_RATE = float(
            os.getenv("FAITHFULNESS_JUDGE_SAMPLE_RATE", "1.0"))
        
        # Text Chunking Configuration
        self.CHUNK_STRATEGY = os.getenv("CHUNK_STRATEGY", "semantic")
        self.CHUNK_SIZE = int(os.getenv("CHUNK_SIZE", "500"))
        self.CHUNK_OVERLAP = int(os.getenv("CHUNK_OVERLAP", "50"))
        self.MIN_CHUNK_SIZE = int(os.getenv("MIN_CHUNK_SIZE", "100"))
        
        # Tesseract OCR Configuration（默认为空，由 parser.py 自动检测）
        self.TESSERACT_PATH = os.getenv("TESSERACT_PATH", "")
        
        # Temporary Files Configuration
        self.TEMP_DIR = os.getenv("TEMP_DIR", "./temp")
        # 确保临时目录存在
        os.makedirs(self.TEMP_DIR, exist_ok=True)
        
        # Logging Configuration
        self.LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
        
        # API Configuration
        self.API_HOST = os.getenv("API_HOST", "0.0.0.0")
        self.API_PORT = int(os.getenv("API_PORT", "8080"))
        
        # CORS Configuration
        self.CORS_ORIGINS = os.getenv("CORS_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000").split(",")
    
    def _setup_logging(self):
        """设置日志配置"""
        log_level = getattr(logging, self.LOG_LEVEL.upper(), logging.INFO)
        logging.basicConfig(
            level=log_level,
            format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
            handlers=[
                logging.StreamHandler()
            ]
        )
        self.logger = logging.getLogger(__name__)
        self.logger.info("Configuration loaded successfully")
    
    def get_config(self) -> Dict[str, Any]:
        """获取所有配置项"""
        return {
            "DASHSCOPE_API_KEY": "***" if self.DASHSCOPE_API_KEY else "",
            "MILVUS_HOST": self.MILVUS_HOST,
            "MILVUS_PORT": self.MILVUS_PORT,
            "USE_MILVUS": self.USE_MILVUS,
            "VECTOR_STORE_PERSIST_DIR": self.VECTOR_STORE_PERSIST_DIR,
            "VECTOR_STORE_COLLECTION_NAME": self.VECTOR_STORE_COLLECTION_NAME,
            "EMBEDDING_MODEL": self.EMBEDDING_MODEL,
            "LOCAL_EMBEDDING_MODEL": self.LOCAL_EMBEDDING_MODEL,
            "LLM_MODEL": self.LLM_MODEL,
            "LLM_BASE_URL": self.LLM_BASE_URL,
            "RERANKER_TYPE": self.RERANKER_TYPE,
            "WEB_SEARCH_ENABLED": self.WEB_SEARCH_ENABLED,
            "WEB_SEARCH_MODE": self.WEB_SEARCH_MODE,
            "WEB_SEARCH_PROVIDER": self.WEB_SEARCH_PROVIDER,
            "TAVILY_API_KEY": "***" if self.TAVILY_API_KEY else "",
            "WEB_SEARCH_TIMEOUT_MS": self.WEB_SEARCH_TIMEOUT_MS,
            "WEB_SEARCH_TOP_K": self.WEB_SEARCH_TOP_K,
            "FAITHFULNESS_JUDGE_ENABLED": self.FAITHFULNESS_JUDGE_ENABLED,
            "FAITHFULNESS_JUDGE_SAMPLE_RATE": self.FAITHFULNESS_JUDGE_SAMPLE_RATE,
            "CHUNK_STRATEGY": self.CHUNK_STRATEGY,
            "CHUNK_SIZE": self.CHUNK_SIZE,
            "CHUNK_OVERLAP": self.CHUNK_OVERLAP,
            "MIN_CHUNK_SIZE": self.MIN_CHUNK_SIZE,
            "TESSERACT_PATH": self.TESSERACT_PATH,
            "TEMP_DIR": self.TEMP_DIR,
            "LOG_LEVEL": self.LOG_LEVEL,
            "API_HOST": self.API_HOST,
            "API_PORT": self.API_PORT,
            "CORS_ORIGINS": self.CORS_ORIGINS
        }
    
    def validate_config(self) -> bool:
        """验证配置项"""
        is_valid = True
        
        # 验证必要的配置项
        if not self.DASHSCOPE_API_KEY:
            self.logger.warning("DASHSCOPE_API_KEY not set, will use local embeddings")
        
        # Tesseract 路径探测: 未显式配置时按常见安装位置回填 self.TESSERACT_PATH。
        # 必须在 config 初始化期间完成 —— core/llm.py 在模块导入时读这个值来设置
        # pytesseract 的可执行文件路径; 而 parser.py 的 setup_tesseract() 是模块级副作用,
        # 若某条链路只走问答、不解析文档(不导入 parser), OCR 会直接抛 TesseractNotFoundError。
        if self.TESSERACT_PATH and not os.path.exists(self.TESSERACT_PATH):
            self.logger.warning(f"Tesseract not found at {self.TESSERACT_PATH}, will try other locations")
            self.TESSERACT_PATH = ""
        if not self.TESSERACT_PATH:
            for _candidate in (
                r"C:/Program Files/Tesseract-OCR/tesseract.exe",
                r"C:/Program Files (x86)/Tesseract-OCR/tesseract.exe",
                "E:/Tesseract-OCR/tesseract.exe",
                "/usr/bin/tesseract",
                "/usr/local/bin/tesseract",
            ):
                if os.path.exists(_candidate):
                    self.TESSERACT_PATH = _candidate
                    self.logger.info(f"Tesseract resolved to: {_candidate}")
                    break

        # 联网补救配置 —— 启动期就要"大声"报出来:
        # mode=tool 但没配 key 时, 每次知识库零命中都会静默跳过联网,
        # 与"压根没接联网"现象一致, 等请求回来再查要多绕一大圈
        _valid_modes = ("bailian", "tool", "off")
        if self.WEB_SEARCH_MODE not in _valid_modes:
            self.logger.warning(
                f"WEB_SEARCH_MODE='{self.WEB_SEARCH_MODE}' is invalid "
                f"(expected one of {list(_valid_modes)}), web search will be skipped")
        elif not self.WEB_SEARCH_ENABLED:
            self.logger.info("WEB_SEARCH_ENABLED=false, web search disabled")
        elif self.WEB_SEARCH_MODE == "tool" and not self.TAVILY_API_KEY:
            self.logger.warning(
                "WEB_SEARCH_MODE=tool but TAVILY_API_KEY is empty — "
                "web search will be SILENTLY SKIPPED on every knowledge-base miss. "
                "Set TAVILY_API_KEY, or switch WEB_SEARCH_MODE to 'bailian'")

        return is_valid

# 创建全局配置实例
config = ConfigManager()
