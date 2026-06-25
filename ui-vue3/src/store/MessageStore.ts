/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { defineStore } from 'pinia'
import { type MessageInfo, type SimpleType } from 'ant-design-x-vue'
import { reactive } from 'vue'
import { type MessageState, type MsgType } from '@/types/message'
import { parseJsonTextStrict } from '@/utils/jsonParser'
import type { UploadedFile } from '@/types/upload'
import { findConvInfo } from '@/db/conversationDB'
export const useMessageStore = <Message extends SimpleType>() =>
  defineStore('messageStore', {
    state(): MsgType<Message> {
      return reactive({
        convId: '',
        currentState: {} as MessageState<Message>,
        history: [] as MessageInfo<any>[],
        htmlReport: [] as string[],
        report: {} as { [key: string]: any[] },
        uploadedFiles: [] as UploadedFile[],
      })
    },
    getters: {
      // 获取消息列表
      messages: (state): MessageInfo<string>[] => {
        return state.history || []
      },
      // 获取当下消息状态
      current: (state): MessageState<Message> => {
        return state.currentState || ({} as MessageState<Message>)
      },
    },
    actions: {
      async init(convId: string) {
        this.convId = convId
        const { conv_messages } = await findConvInfo(convId)
        if (conv_messages) {
          this.currentState = conv_messages.currentState
          this.history = conv_messages.history
          this.htmlReport = conv_messages.htmlReport
          this.report = conv_messages.report
          this.uploadedFiles = conv_messages.uploadedFiles
        } else {
          this.currentState = {} as MessageState<Message>
          this.history = [] as MessageInfo<any>[]
          this.htmlReport = [] as string[]
          this.report = {} as { [key: string]: any[] }
          this.uploadedFiles = [] as UploadedFile[]
        }
      },
      nextAIType() {
        if (!this.current.aiType || this.current.aiType === 'normal') {
          this.current.aiType = 'startDS'
        } else if (this.current.aiType === 'startDS') {
          this.current.aiType = 'onDS'
        } else if (this.current.aiType === 'onDS') {
          this.current.aiType = 'endDS'
        } else {
          this.current.aiType = 'normal'
        }
      },
      addReport(report: any) {
        if (!report) {
          return
        }
        const node = JSON.parse(report)
        if (!this.report[node.graphId.thread_id]) {
          this.report[node.graphId.thread_id] = []
        } else {
          this.report[node.graphId.thread_id].push(node)
        }
      },
      isEnd(threadId: string): boolean {
        const report = this.report[threadId]
        if (!report) {
          return false
        }
        for (const item of report) {
          if (item.nodeName === '__END__') {
            return true
          }
        }
        return false
      },
      // 添加文件到指定会话
      addUploadedFile(file: UploadedFile) {
        if (!this.uploadedFiles) {
          this.uploadedFiles = []
        }
        this.uploadedFiles.push(file)
      },
      // 移除指定会话的文件
      removeUploadedFile(fileId: string) {
        this.uploadedFiles = this.uploadedFiles.filter((file: UploadedFile) => file.uid !== fileId)
      },
      // 更新文件状态
      updateFileStatus(fileId: string, status: UploadedFile['status']) {
        const file = this.uploadedFiles.find((f: UploadedFile) => f.uid === fileId)
        if (file) {
          file.status = status
        }
      },
    },
    // persist: true,
  })()
