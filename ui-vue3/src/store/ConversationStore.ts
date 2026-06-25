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
import { MessageOutlined } from '@ant-design/icons-vue'
import { h, reactive } from 'vue'
import { v1, v3, v4, v5 } from 'uuid'
import { useRoute, useRouter } from 'vue-router'
import { addConvInfo } from '@/db/conversationDB'
import { useMessageStore } from '@/store/MessageStore'
import { toRaw } from 'vue-demi'
import { deepToRaw } from '@/utils/proxy'
import { message as AntMessage } from 'ant-design-vue/es/components'
export const useConversationStore = () =>
  defineStore('conversationStore', {
    state(): { editKey: string | null; current: number; conversations: any } {
      return reactive({
        current: 0,
        editKey: null,
        conversations: [],
      })
    },
    getters: {
      curConv: state => state.conversations[state.current],
      curConvKey: state => state.conversations[state.current]?.key,
    },
    actions: {
      async newOne(firstMessage: any | null = null) {
        const messageStore = useMessageStore()
        if (messageStore.current?.runFlag) {
          AntMessage.warn('对话正在进行，无法新建对话')
          return
        }
        const newVar = {
          key: v4(),
          title: firstMessage || 'Unnamed conversation',
          messages: null,
        }
        const message = useMessageStore()
        // save lastMessage
        await addConvInfo(message.convId, deepToRaw(message))
        await message.init(newVar.key)
        this.conversations = [newVar, ...this.conversations]
        this.current++
        return newVar
      },
      updateTitle(key: any, title: any) {
        this.conversations.map((item: any) => {
          if (item.key === key && item.title === 'Unnamed conversation') {
            item.title = title
          }
        })
      },
      contains(key: any) {
        return (
          this.conversations.filter((item: any) => {
            return item.key === key
          }).length > 0
        )
      },
      delete(key: any) {
        this.conversations = this.conversations.filter((item: any) => {
          return item.key !== key
        })
      },
      active(convId: any) {
        if (!convId) this.current = -1
        for (let i = 0; i < this.conversations.length; i++) {
          if (this.conversations[i].key === convId) {
            this.current = i
            break
          }
        }
      },
      clearAll() {
        this.conversations = []
        this.current = -1
        this.editKey = null
      },
    },
    persist: true,
  })()
