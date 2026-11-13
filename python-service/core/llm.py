import os
import json
import requests
from typing import AsyncGenerator, Generator
from langchain_openai import ChatOpenAI
from langchain_core.prompts import PromptTemplate
from langchain_core.output_parsers import StrOutputParser
from PIL import Image
import pytesseract

# 使用统一配置管理模块
from core.config import config

# 配置Tesseract OCR路径（空值时由 parser.py 自动检测）
if config.TESSERACT_PATH:
    pytesseract.pytesseract.tesseract_cmd = config.TESSERACT_PATH

class LLMService:
    def __init__(self):
        # 默认使用阿里云通义千问 (需要设置 DASHSCOPE_API_KEY 环境变量)
        # LLM_API_KEY 可单独覆盖(切换到其他厂商时使用), 未配置则沿用 DASHSCOPE_API_KEY
        api_key = config.LLM_API_KEY
        
        if not api_key:
            config.logger.warning("DASHSCOPE_API_KEY not found. LLM features will not work properly.")
            self.llm = None
        else:
            # 模型 / 端点均由配置注入(见 core/config.py), 默认走百炼兼容模式
            # 切换模型: 在 .env 设置 LLM_MODEL 即可(如 qwen-plus / deepseek-v4.1-flash / kimi-k3.1-pro)
            # 注意模型名带版本号且会下架, 404 说明名字失效、403 FreeTierOnly 说明该模型额度用尽
            self.llm = ChatOpenAI(
                model=config.LLM_MODEL,
                api_key=api_key,
                base_url=config.LLM_BASE_URL,
                streaming=True  # 启用流式输出
            )
            # 启动日志打印当前模型, 便于确认切换是否生效
            config.logger.info(
                f"LLM initialized: model={config.LLM_MODEL}, base_url={config.LLM_BASE_URL}")

        # 优化后的 Prompt 模板
        # 支持对话上下文和知识库上下文
        self.prompt = PromptTemplate.from_template(
            """
            你是一个专业的AI知识库助手。请根据提供的知识库信息回答用户问题。

            重要规则：
            1. 如果知识库资料能回答问题，优先基于资料回答并引用对应来源
            2. 如果资料只覆盖了问题的一部分：先基于资料回答覆盖的部分，再对未覆盖的部分
               补充你自己的知识，并明确标注"（以下为通用知识补充）"——不要笼统地说"知识库中未找到"
            3. 只有当知识库资料与问题完全无关时，才说明"知识库中未找到相关信息，以下是我的理解："
            4. 如果是问候、自我介绍等问题，可以直接回答，不需要强行引用知识库
            5. 回答要自然、友好，避免机械和死板
            6. 不要提及"AI服务不可用"、"系统错误"等技术问题，你始终处于正常工作状态

            对话历史（仅供参考，可能包含过时信息）：
            {conversation_context}

            相关知识库：
            {knowledge_context}

            用户当前问题：
            {question}

            请给出自然、友好的回答：
            """
        )

        # 标题生成模板
        self.summary_prompt = PromptTemplate.from_template(
            """
            请为以下用户问题生成一个简短的标题（Summary）。
            
            用户问题：
            {question}
            
            要求：
            1. 标题应概括问题的主要内容。
            2. 长度控制在10个字以内。
            3. 不需要任何前缀或后缀，直接返回标题文本。
            
            标题：
            """
        )

    """
     * 获取 LLM 的回答
     * @param question 用户问题
     * @param context_docs 上下文文档列表
     * @param conversation_context 对话上下文（可选）
     * @return LLM 的回答
     * """
    def get_answer(self, question: str, context_docs: list, conversation_context: str = "",
                   enable_search: bool = False) -> str:
        import time
        start_time = time.time()
        
        if not self.llm:
            # 当没有API密钥时，返回一个友好的默认响应
            config.logger.info(f"LLM get_answer completed in {time.time() - start_time:.4f}s (no API key)")
            return "我是AI知识库助手，很高兴为您服务。由于系统未配置API密钥，我暂时无法提供详细回答。请联系管理员配置DASHSCOPE_API_KEY环境变量以启用完整功能。"

        # 处理包含图片的问题
        image_process_start = time.time()
        processed_question = self.process_question_with_images(question)
        image_process_time = time.time() - image_process_start
        config.logger.info(f"Image processing completed in {image_process_time:.4f}s")

        # 处理知识库上下文
        if not context_docs:
            if enable_search:
                # 联网场景必须换掉"（无相关知识库信息）"这句 —— 实测它会
                # 让模型认定"无资料可用", 从而忽略服务端注入的联网结果,
                # 表现为"参数生效了却看不出效果"(退化成纯通用知识回答)。
                # 换成引导句后, 模型会主动利用检索结果并注明信息来自网络。
                knowledge_context = (
                    "（知识库中无相关资料。请结合联网检索到的最新信息作答，"
                    "并说明信息来自网络）")
            else:
                knowledge_context = "（无相关知识库信息）"
        else:
            knowledge_context = "\n\n".join([
                doc.page_content if hasattr(doc, 'page_content') else str(doc)
                for doc in context_docs
            ])

        # 处理对话上下文 - 过滤掉错误信息
        cleaned_context = self.clean_conversation_context(conversation_context)
        if not cleaned_context or cleaned_context.strip() == "":
            cleaned_context = "（无对话历史）"

        # 构建处理链
        chain = (
            self.prompt
            | self.llm
            | StrOutputParser()
        )
        prompt_args = {
            "conversation_context": cleaned_context,
            "knowledge_context": knowledge_context,
            "question": processed_question
        }
        # 临时诊断: 确认 enable_search 是否真的传到位
        config.logger.info(
            f"[LLM-DIAG] get_answer: enable_search={enable_search}, "
            f"docs={len(context_docs) if context_docs else 0}, "
            f"kc={knowledge_context[:36]!r}")

        try:
            llm_start = time.time()
            if enable_search:
                # enable_search 必须作为 invoke 的 kwargs 直接传给模型 ——
                # 实测把它 bind 到 chain 上(prompt | llm.bind(extra_body=...) | parser)
                # 该参数会在管道中丢失, 表现为"看起来开了联网其实没开"。
                # 因此开网时绕开 chain, 手动组装 messages 后直接调用。
                # (百炼在服务端完成检索并把结果注入 prompt, 费用走同一次调用的 token)
                result = self.llm.invoke(
                    self.prompt.format(**prompt_args),
                    extra_body={"enable_search": True},
                ).content
            else:
                result = chain.invoke(prompt_args)
            llm_time = time.time() - llm_start
            config.logger.info(f"LLM invocation completed in {llm_time:.4f}s")
            config.logger.info(f"LLM get_answer completed in {time.time() - start_time:.4f}s")
            return result
        except Exception as e:
            config.logger.error(f"LLM Error: {e}")
            config.logger.info(f"LLM get_answer completed in {time.time() - start_time:.4f}s (error)")
            return "抱歉，我暂时无法回答这个问题，请稍后再试。"

    def clean_conversation_context(self, context: str) -> str:
        """
        清理对话上下文，移除错误信息，防止污染后续回答
        """
        if not context:
            return ""
        
        # 需要过滤的错误关键词
        error_keywords = [
            "AI服务暂时不可用",
            "服务不可用",
            "系统错误",
            "无法连接",
            "网络错误",
            "超时",
            "API密钥",
            "配置错误"
        ]
        
        # 按行分割
        lines = context.split("\n")
        # 过滤包含错误关键词的行
        cleaned_lines = [
            line for line in lines 
            if not any(keyword in line for keyword in error_keywords)
        ]
        
        return "\n".join(cleaned_lines)

    """
     * 流式获取 LLM 的回答
     * @param question 用户问题
     * @param context_docs 上下文文档列表
     * @param conversation_context 对话上下文（可选）
     * @return 流式生成器，逐个token返回
     * """
    def get_answer_stream(self, question: str, context_docs: list, conversation_context: str = "",
                          enable_search: bool = False) -> Generator[str, None, None]:
        import time
        start_time = time.time()
        
        if not self.llm:
            # 当没有API密钥时，返回错误信息
            config.logger.info(f"LLM get_answer_stream completed in {time.time() - start_time:.4f}s (no API key)")
            yield json.dumps({"type": "error", "content": "未配置API密钥"})
            return

        # 处理包含图片的问题
        image_process_start = time.time()
        processed_question = self.process_question_with_images(question)
        image_process_time = time.time() - image_process_start
        config.logger.info(f"Image processing completed in {image_process_time:.4f}s")

        # 处理知识库上下文
        if not context_docs:
            if enable_search:
                # 联网场景必须换掉"（无相关知识库信息）"这句 —— 实测它会
                # 让模型认定"无资料可用", 从而忽略服务端注入的联网结果,
                # 表现为"参数生效了却看不出效果"(退化成纯通用知识回答)。
                # 换成引导句后, 模型会主动利用检索结果并注明信息来自网络。
                knowledge_context = (
                    "（知识库中无相关资料。请结合联网检索到的最新信息作答，"
                    "并说明信息来自网络）")
            else:
                knowledge_context = "（无相关知识库信息）"
        else:
            knowledge_context = "\n\n".join([
                doc.page_content if hasattr(doc, 'page_content') else str(doc)
                for doc in context_docs
            ])

        # 处理对话上下文 - 过滤掉错误信息
        cleaned_context = self.clean_conversation_context(conversation_context)
        if not cleaned_context or cleaned_context.strip() == "":
            cleaned_context = "（无对话历史）"

        # 构建处理链
        chain = (
            self.prompt
            | self.llm
            | StrOutputParser()
        )
        prompt_args = {
            "conversation_context": cleaned_context,
            "knowledge_context": knowledge_context,
            "question": processed_question
        }

        try:
            # 发送开始信号
            yield json.dumps({"type": "start", "content": ""})

            # 流式调用
            llm_start = time.time()
            full_response = ""
            if enable_search:
                # 同 get_answer: 开网时必须绕开 chain 直接传 extra_body,
                # 否则参数会在管道中丢失(表现为"开了网却没搜")
                for chunk in self.llm.stream(
                    self.prompt.format(**prompt_args),
                    extra_body={"enable_search": True},
                ):
                    text = chunk.content
                    full_response += text
                    yield json.dumps({"type": "token", "content": text})
            else:
                for chunk in chain.stream(prompt_args):
                    full_response += chunk
                    yield json.dumps({"type": "token", "content": chunk})
            llm_time = time.time() - llm_start
            config.logger.info(f"LLM stream invocation completed in {llm_time:.4f}s")

            # 发送结束信号
            yield json.dumps({"type": "end", "content": full_response})
            config.logger.info(f"LLM get_answer_stream completed in {time.time() - start_time:.4f}s")

        except Exception as e:
            config.logger.error(f"LLM Stream Error: {e}")
            config.logger.info(f"LLM get_answer_stream completed in {time.time() - start_time:.4f}s (error)")
            yield json.dumps({"type": "error", "content": "暂时无法回答，请稍后再试"})

    def generate_title(self, question: str) -> str:
        import time
        start_time = time.time()
        
        if not self.llm:
            config.logger.info(f"LLM generate_title completed in {time.time() - start_time:.4f}s (no API key)")
            return "New Chat"

        chain = (
            self.summary_prompt
            | self.llm
            | StrOutputParser()
        )
        
        try:
            llm_start = time.time()
            title = chain.invoke({"question": question})
            llm_time = time.time() - llm_start
            # 清理可能的额外空白或引号
            result = title.strip().strip('"').strip("'")
            config.logger.info(f"LLM title generation completed in {llm_time:.4f}s")
            config.logger.info(f"LLM generate_title completed in {time.time() - start_time:.4f}s")
            return result
        except Exception as e:
            config.logger.error(f"LLM Title Generation Error: {e}")
            config.logger.info(f"LLM generate_title completed in {time.time() - start_time:.4f}s (error)")
            return "New Chat"

    def extract_text_from_image(self, image_url: str) -> str:
        """
        从图片URL中提取文字
        """
        try:
            # 处理相对路径，转换为完整URL
            if image_url.startswith('/api/'):
                # 使用后端服务地址
                image_url = f"http://localhost:8080{image_url}"
            
            config.logger.info(f"Downloading image from: {image_url}")
            
            # 下载图片
            response = requests.get(image_url, timeout=10)
            response.raise_for_status()
            
            # 保存到临时文件
            temp_path = os.path.join(config.TEMP_DIR, "temp_image.png")
            with open(temp_path, "wb") as f:
                f.write(response.content)
            
            config.logger.info(f"Image saved to temp file, size: {len(response.content)} bytes")
            
            # 使用OCR提取文字
            image = Image.open(temp_path)
            text = pytesseract.image_to_string(image, lang='chi_sim+eng')
            
            config.logger.info(f"OCR result: {text[:100]}...")  # 打印前100个字符
            
            # 清理临时文件
            if os.path.exists(temp_path):
                os.remove(temp_path)
            
            return text.strip() if text.strip() else "图片中未识别到文字"
        except Exception as e:
            config.logger.error(f"Error extracting text from image: {e}")
            return f"无法从图片中提取文字: {str(e)}"

    def process_question_with_images(self, question: str) -> str:
        """
        处理包含图片URL的问题，提取图片中的文字并添加到问题中
        """
        import re
        # 查找图片URL（支持完整URL和相对路径）
        image_urls = re.findall(r'图片URL: (/api/[^\n]+)', question)
        
        config.logger.info(f"Found image URLs: {image_urls}")
        
        if image_urls:
            processed_question = question
            for image_url in image_urls:
                # 提取图片中的文字
                image_text = self.extract_text_from_image(image_url)
                # 将图片文字添加到问题中
                processed_question += f"\n\n图片内容: {image_text}"
            return processed_question
        else:
            return question

    def generate(self, prompt: str, temperature: float = 0.7, max_tokens: int = 150) -> str:
        """
        简单的文本生成方法（用于闲聊等场景）
        """
        import time
        start_time = time.time()
        
        if not self.llm:
            config.logger.info(f"LLM generate completed in {time.time() - start_time:.4f}s (no API key)")
            return "我是AI助手，很高兴为您服务。"
        
        try:
            # 使用简单的 prompt
            simple_prompt = PromptTemplate.from_template("{input}")
            chain = simple_prompt | self.llm.bind(temperature=temperature, max_tokens=max_tokens) | StrOutputParser()
            
            result = chain.invoke({"input": prompt})
            
            config.logger.info(f"LLM generate completed in {time.time() - start_time:.4f}s")
            return result
        except Exception as e:
            config.logger.error(f"LLM generate Error: {e}")
            return "抱歉，我暂时无法回答这个问题。"

    def generate_stream(self, prompt: str, temperature: float = 0.7,
                        max_tokens: int = 150,
                        fallback_text: str = "抱歉，我暂时无法回答这个问题。") -> Generator[str, None, None]:
        """流式文本生成（闲聊等"直接给 prompt 生成"的场景）

        与 get_answer_stream 的区别:
        - get_answer_stream: 走知识库问答模板, 输出的是 JSON 事件字符串（token/end）
        - generate_stream : 输出的是**纯文本增量**, 由调用方决定怎么包装成事件

        为什么需要它: 原闲聊链路是"先 llm.generate() 整段算完 → 再 for char in answer
        逐字 yield"——用户实际要等完整生成时间才看到第一个字, 逐字只是回放。
        换成 chain.stream() 后, 首字延迟从"整段生成时间"降到"首个 token 时间"。
        """
        import time
        start_time = time.time()

        if not self.llm:
            config.logger.info(f"LLM generate_stream completed in {time.time() - start_time:.4f}s (no API key)")
            if fallback_text:
                yield fallback_text
            return

        try:
            simple_prompt = PromptTemplate.from_template("{input}")
            # enable_thinking=False: 本模型是"思考+输出"共享 max_tokens 预算的推理模型。
            # 思考开启时: 首 token 延迟翻倍(实测 2.4s→1.3s), 且思考越长可见输出越早被截断
            # （历史 bug: 报告解读随机在半句话处断掉）。闲聊/解读这类短任务无需思考。
            chain = (
                simple_prompt
                | self.llm.bind(temperature=temperature, max_tokens=max_tokens,
                                extra_body={"enable_thinking": False})
                | StrOutputParser()
            )
            first_token_at = None
            for chunk in chain.stream({"input": prompt}):
                if not chunk:
                    continue
                if first_token_at is None:
                    first_token_at = time.time() - start_time
                yield chunk
            config.logger.info(
                f"LLM generate_stream completed in {time.time() - start_time:.4f}s "
                f"(首 token {first_token_at:.4f}s)" if first_token_at is not None
                else f"LLM generate_stream completed in {time.time() - start_time:.4f}s (无 token)")
        except Exception as e:
            # fallback_text 为空字符串表示"调用方自己兜底": 例如报告解读场景,
            # 报告数据已先行下发, 这里静默跳过即可, 不应把错误话术拼进报告
            config.logger.error(f"LLM generate_stream Error: {e}")
            if fallback_text:
                yield fallback_text

    def generate_report_insight_stream(self, report_text: str,
                                       report_label: str) -> Generator[str, None, None]:
        """把结构化报告交给 LLM 做运营解读（核心结论/风险/行动建议）, 真流式返回

        供运营报告与管理助手各报告共用（提示词单一事实来源, 避免多处拷贝走样）。

        设计原则: LLM 只做"解读层"、不替代数据 —— 报告数字必须来自数据库,
        prompt 明确禁止编造数字, 避免指标幻觉。
        LLM 不可用或调用失败时静默返回, 调用方自动回退为纯报告。
        """
        if not report_text or not self.llm:
            return

        prompt = f"""你是资深运营分析师。下面是一份系统自动生成的《{report_label}》数据报告。
请**只依据报告中的真实数据**（不要编造任何数字或指标）给出运营解读：

1. **核心结论**：2-3 条，每条一句话，说明数据反映了什么问题
2. **风险与异常**：需要关注的数据或趋势（包括数据之间互相矛盾的地方）
3. **行动建议**：2-3 条具体、可执行的建议

格式要求：Markdown 小标题分节，总字数 350 字以内，不要重复罗列原始数据。

数据报告：
{report_text[:3000]}"""

        yield "\n\n## 🤖 智能解读\n\n"
        # fallback_text="" —— 失败静默跳过: 数据已先行下发, 不把错误话术拼进报告
        yield from self.generate_stream(prompt, temperature=0.3, max_tokens=800,
                                        fallback_text="")


# 创建单例实例
llm_service = LLMService()

# 导出（保持兼容性）
llm = llm_service