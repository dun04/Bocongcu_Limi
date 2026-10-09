package com.xiaomi.fixnotification.ai

import com.xiaomi.fixnotification.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Html
import android.text.Spanned
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LimiChatAdapter(
    private val messages: MutableList<LimiChatMessage> = mutableListOf(),
    var onActionClick: ((ChatActionButton) -> Unit)? = null,
    var onFeedbackThumbUp: ((LimiChatMessage, Int) -> Unit)? = null,
    var onFeedbackThumbDown: ((LimiChatMessage, Int) -> Unit)? = null,
    var onFeedbackContribute: ((LimiChatMessage, Int) -> Unit)? = null,
    var onImageClick: ((android.graphics.Bitmap) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_USER = 1
        const val TYPE_LIMI = 2
        const val PAYLOAD_TEXT_ONLY = "PAYLOAD_TEXT_ONLY"
        const val PAYLOAD_FEEDBACK_ONLY = "PAYLOAD_FEEDBACK_ONLY"
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvUserText: TextView = itemView.findViewById(R.id.tvUserMessageText)
        val tvUserTime: TextView = itemView.findViewById(R.id.tvUserTimestamp)
        val cardUserImage: com.google.android.material.card.MaterialCardView? = itemView.findViewById(R.id.cardUserMessageImage)
        val ivUserImage: ImageView? = itemView.findViewById(R.id.ivUserMessageImage)
        val scrollUserMultiImages: android.widget.HorizontalScrollView? = itemView.findViewById(R.id.scrollUserMultiImages)
        val layoutUserMultiImagesContainer: LinearLayout? = itemView.findViewById(R.id.layoutUserMultiImagesContainer)
    }

    class LimiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvLimiText: TextView = itemView.findViewById(R.id.tvLimiMessageText)
        val layoutLimiRichSections: LinearLayout? = itemView.findViewById(R.id.layoutLimiRichSections)
        val tvLimiTime: TextView = itemView.findViewById(R.id.tvLimiTimestamp)
        val btnCopy: ImageView = itemView.findViewById(R.id.btnCopyAiMessage)
        val cardLimiImage: com.google.android.material.card.MaterialCardView? = itemView.findViewById(R.id.cardLimiMessageImage)
        val ivLimiImage: ImageView? = itemView.findViewById(R.id.ivLimiMessageImage)
        val cardAppPreview: com.google.android.material.card.MaterialCardView? = itemView.findViewById(R.id.cardAppPreview)
        val ivAppPreviewIcon: ImageView? = itemView.findViewById(R.id.ivAppPreviewIcon)
        val tvAppPreviewName: TextView? = itemView.findViewById(R.id.tvAppPreviewName)
        val tvAppPreviewPackage: TextView? = itemView.findViewById(R.id.tvAppPreviewPackage)
        val cardBloatwareList: com.google.android.material.card.MaterialCardView? = itemView.findViewById(R.id.cardBloatwareList)
        val tvBloatwareHeader: TextView? = itemView.findViewById(R.id.tvBloatwareHeader)
        val btnToggleSelectAllBloat: MaterialButton? = itemView.findViewById(R.id.btnToggleSelectAllBloat)
        val layoutBloatwareItemsContainer: LinearLayout? = itemView.findViewById(R.id.layoutBloatwareItemsContainer)
        val layoutActionButtons: LinearLayout = itemView.findViewById(R.id.layoutActionButtons)
        val layoutFeedbackBar: LinearLayout? = itemView.findViewById(R.id.layoutFeedbackBar)
        val layoutFeedbackIcons: LinearLayout? = itemView.findViewById(R.id.layoutFeedbackIcons)
        val btnFeedbackThumbUpContainer: View? = itemView.findViewById(R.id.btnFeedbackThumbUpContainer)
        val btnFeedbackThumbUp: ImageView? = itemView.findViewById(R.id.btnFeedbackThumbUp)
        val tvFeedbackThumbUpCount: TextView? = itemView.findViewById(R.id.tvFeedbackThumbUpCount)
        val btnFeedbackThumbDownContainer: View? = itemView.findViewById(R.id.btnFeedbackThumbDownContainer)
        val btnFeedbackThumbDown: ImageView? = itemView.findViewById(R.id.btnFeedbackThumbDown)
        val tvFeedbackThumbDownCount: TextView? = itemView.findViewById(R.id.tvFeedbackThumbDownCount)
        val btnFeedbackContribute: LinearLayout? = itemView.findViewById(R.id.btnFeedbackContribute)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].sender == MessageSender.USER) TYPE_USER else TYPE_LIMI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            val view = inflater.inflate(R.layout.item_chat_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_chat_limi, parent, false)
            LimiViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_TEXT_ONLY)) {
            val msg = messages[position]
            if (holder is LimiViewHolder) {
                if (msg.richSections.isEmpty()) {
                    holder.tvLimiText.setTextIsSelectable(false)
                    holder.tvLimiText.setOnTouchListener(null)
                    holder.tvLimiText.text = LimiMarkdownFormatter.format(holder.itemView.context, msg.text)
                }
                return
            } else if (holder is UserViewHolder) {
                holder.tvUserText.text = msg.text
                return
            }
        }
        if (payloads.contains(PAYLOAD_FEEDBACK_ONLY)) {
            val msg = messages[position]
            if (holder is LimiViewHolder) {
                bindFeedbackState(holder, msg)
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date(msg.timestamp))

        if (holder is UserViewHolder) {
            val context = holder.itemView.context
            holder.tvUserText.text = msg.text
            holder.tvUserTime.text = timeStr

            val allUserImages = mutableListOf<android.graphics.Bitmap>()
            if (msg.imageBitmaps.isNotEmpty()) {
                allUserImages.addAll(msg.imageBitmaps)
            } else if (msg.imageBitmap != null) {
                allUserImages.add(msg.imageBitmap!!)
            }

            if (allUserImages.size == 1) {
                holder.cardUserImage?.visibility = View.VISIBLE
                holder.scrollUserMultiImages?.visibility = View.GONE
                holder.ivUserImage?.setImageBitmap(allUserImages[0])
                holder.cardUserImage?.setOnClickListener {
                    onImageClick?.invoke(allUserImages[0])
                }
            } else if (allUserImages.size > 1) {
                holder.cardUserImage?.visibility = View.GONE
                holder.scrollUserMultiImages?.visibility = View.VISIBLE
                holder.layoutUserMultiImagesContainer?.removeAllViews()

                val density = context.resources.displayMetrics.density
                val thumbSize = (90 * density).toInt()
                val marginEnd = (6 * density).toInt()

                for (bmp in allUserImages) {
                    val card = com.google.android.material.card.MaterialCardView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                            this.marginEnd = marginEnd
                        }
                        radius = 10 * density
                        strokeWidth = (1 * density).toInt()
                        strokeColor = Color.parseColor("#40FFFFFF")
                        cardElevation = 0f
                        setCardBackgroundColor(Color.parseColor("#15FFFFFF"))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            onImageClick?.invoke(bmp)
                        }
                    }

                    val iv = ImageView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bmp)
                    }
                    card.addView(iv)
                    holder.layoutUserMultiImagesContainer?.addView(card)
                }
            } else {
                holder.cardUserImage?.visibility = View.GONE
                holder.scrollUserMultiImages?.visibility = View.GONE
            }
        } else if (holder is LimiViewHolder) {
            val context = holder.itemView.context
            holder.tvLimiTime.text = timeStr

            // Xử lý hiển thị Rich Sections (Từng phần giới thiệu kèm ảnh riêng ngay bên dưới)
            if (msg.richSections.isNotEmpty() && holder.layoutLimiRichSections != null) {
                holder.tvLimiText.visibility = View.GONE
                holder.cardLimiImage?.visibility = View.GONE
                holder.layoutLimiRichSections.visibility = View.VISIBLE
                holder.layoutLimiRichSections.removeAllViews()

                for (sec in msg.richSections) {
                    if (sec.text.isNotBlank()) {
                        val tv = TextView(context).apply {
                            text = LimiMarkdownFormatter.format(holder.itemView.context, sec.text)
                            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                            setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3.5f, context.resources.displayMetrics), 1.0f)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics).toInt()
                                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics).toInt()
                            }
                        }
                        LimiLinkTouchHelper.setup(tv)
                        holder.layoutLimiRichSections.addView(tv)
                    }

                    if (sec.imageBitmap != null) {
                        val card = createSectionImageCard(context, sec.imageBitmap!!, onImageClick)
                        holder.layoutLimiRichSections.addView(card)
                    }
                }
            } else {
                holder.layoutLimiRichSections?.visibility = View.GONE
                holder.tvLimiText.visibility = View.VISIBLE
                holder.tvLimiText.text = LimiMarkdownFormatter.format(holder.itemView.context, msg.text)
                LimiLinkTouchHelper.setup(holder.tvLimiText)

                // Hiển thị ảnh đơn của Limi nếu có
                if (msg.imageBitmap != null) {
                    holder.cardLimiImage?.visibility = View.VISIBLE
                    holder.ivLimiImage?.setImageBitmap(msg.imageBitmap)
                    holder.cardLimiImage?.setOnClickListener {
                        msg.imageBitmap?.let { bmp -> onImageClick?.invoke(bmp) }
                    }
                } else {
                    holder.cardLimiImage?.visibility = View.GONE
                }
            }

            holder.btnCopy.setOnClickListener {
                val ctx = holder.itemView.context
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                // Trích xuất văn bản đã qua định dạng đẹp mắt của Limi (loại bỏ markdown thô, bảng ngay ngắn, biểu tượng rõ ràng)
                val formatted = if (msg.richSections.isNotEmpty()) {
                    val sb = SpannableStringBuilder()
                    for (sec in msg.richSections) {
                        if (sec.text.isNotBlank()) {
                            if (sb.isNotEmpty()) sb.append("\n\n")
                            sb.append(LimiMarkdownFormatter.format(ctx, sec.text))
                        }
                    }
                    sb
                } else {
                    LimiMarkdownFormatter.format(ctx, msg.text)
                }

                val cleanPlainText = formatted.toString().trim()
                val htmlText = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        Html.toHtml(formatted as? Spanned ?: SpannableString(cleanPlainText), Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
                    } else {
                        @Suppress("DEPRECATION")
                        Html.toHtml(formatted as? Spanned ?: SpannableString(cleanPlainText))
                    }
                } catch (_: Throwable) {
                    null
                }

                val clip = if (!htmlText.isNullOrBlank()) {
                    ClipData.newHtmlText("Limi AI Response", cleanPlainText, htmlText)
                } else {
                    ClipData.newPlainText("Limi AI Response", cleanPlainText)
                }
                clipboard.setPrimaryClip(clip)
                Toast.makeText(ctx, "Đã sao chép câu trả lời của Limi", Toast.LENGTH_SHORT).show()
            }

            // Hiển thị Card thông tin & hình ảnh ứng dụng đơn lẻ nếu có
            val preview = msg.appPreview
            if (preview != null && holder.cardAppPreview != null) {
                holder.cardAppPreview.visibility = View.VISIBLE
                holder.tvAppPreviewName?.text = preview.appName
                holder.tvAppPreviewPackage?.text = preview.packageName
                if (preview.icon != null) {
                    holder.ivAppPreviewIcon?.setImageDrawable(preview.icon)
                } else {
                    holder.ivAppPreviewIcon?.setImageResource(R.drawable.ic_default_app_icon)
                }
            } else {
                holder.cardAppPreview?.visibility = View.GONE
            }

            // Hiển thị Card danh sách Bloatware rác cho phép tích chọn từng app & chọn tất cả
            val bloatList = msg.bloatwareList
            if (bloatList.isNotEmpty() && holder.cardBloatwareList != null && holder.layoutBloatwareItemsContainer != null) {
                holder.cardBloatwareList.visibility = View.VISIBLE
                holder.layoutBloatwareItemsContainer.removeAllViews()

                val context = holder.itemView.context
                val inflater = LayoutInflater.from(context)

                fun updateBloatHeaderAndButtons() {
                    val selectedCount = bloatList.count { it.isSelected }
                    val totalCount = bloatList.size
                    holder.tvBloatwareHeader?.text = "📋 Danh sách Bloatware (Đã chọn: $selectedCount/$totalCount)"
                    holder.btnToggleSelectAllBloat?.text = if (selectedCount == totalCount) "Bỏ chọn tất cả" else "Chọn tất cả"

                    // Cập nhật text nút xác nhận nếu có
                    val confirmBtn = msg.actionButtons.firstOrNull { it.actionType == ChatActionType.EXECUTE_UNINSTALL_BLOATWARE }
                    if (confirmBtn != null) {
                        for (i in 0 until holder.layoutActionButtons.childCount) {
                            val child = holder.layoutActionButtons.getChildAt(i)
                            if (child is MaterialButton && child.text.contains("Bloatware")) {
                                child.text = if (selectedCount > 0) "🗑️ Đồng Ý & Xóa $selectedCount Bloatware Đã Chọn" else "🗑️ Xóa 0 Bloatware (Hãy chọn ít nhất 1 app)"
                            }
                        }
                    }
                    LimiAiService.setPendingBloatwareList(bloatList)
                }

                bloatList.forEach { item ->
                    val rowView = inflater.inflate(R.layout.item_chat_bloatware_row, holder.layoutBloatwareItemsContainer, false)
                    val ivIcon: ImageView = rowView.findViewById(R.id.ivBloatItemIcon)
                    val tvName: TextView = rowView.findViewById(R.id.tvBloatItemName)
                    val tvPkg: TextView = rowView.findViewById(R.id.tvBloatItemPackage)
                    val tvDesc: TextView = rowView.findViewById(R.id.tvBloatItemDesc)
                    val cbSelect: com.google.android.material.checkbox.MaterialCheckBox = rowView.findViewById(R.id.cbBloatItemSelect)

                    tvName.text = item.name
                    tvPkg.text = item.packageName
                    if (item.description.isNotEmpty()) {
                        tvDesc.visibility = View.VISIBLE
                        tvDesc.text = item.description
                    } else {
                        tvDesc.visibility = View.GONE
                    }

                    if (item.icon != null) {
                        ivIcon.setImageDrawable(item.icon)
                    } else {
                        ivIcon.setImageResource(R.drawable.ic_default_app_icon)
                    }

                    cbSelect.isChecked = item.isSelected

                    rowView.setOnClickListener {
                        item.isSelected = !item.isSelected
                        cbSelect.isChecked = item.isSelected
                        updateBloatHeaderAndButtons()
                    }

                    holder.layoutBloatwareItemsContainer.addView(rowView)
                }

                updateBloatHeaderAndButtons()

                holder.btnToggleSelectAllBloat?.setOnClickListener {
                    val allSelected = bloatList.all { it.isSelected }
                    val newSelection = !allSelected
                    bloatList.forEach { it.isSelected = newSelection }
                    for (i in 0 until holder.layoutBloatwareItemsContainer.childCount) {
                        val child = holder.layoutBloatwareItemsContainer.getChildAt(i)
                        val cb = child.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cbBloatItemSelect)
                        cb?.isChecked = newSelection
                    }
                    updateBloatHeaderAndButtons()
                }
            } else {
                holder.cardBloatwareList?.visibility = View.GONE
            }

            // Render các nút hành động trực tiếp
            holder.layoutActionButtons.removeAllViews()
            if (msg.actionButtons.isNotEmpty()) {
                holder.layoutActionButtons.visibility = View.VISIBLE
                holder.layoutActionButtons.alpha = 0f
                holder.layoutActionButtons.translationY = 12f
                holder.layoutActionButtons.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(280)
                    .start()
                val context = holder.itemView.context
                val dp6 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, context.resources.displayMetrics).toInt()
                val dp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, context.resources.displayMetrics).toInt()

                msg.actionButtons.forEach { btnData ->
                    val actionBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp6
                        }
                        text = btnData.title
                        textSize = 12.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(context, R.color.primary))
                        cornerRadius = dp12
                        insetTop = 0
                        insetBottom = 0
                        minHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 38f, context.resources.displayMetrics).toInt()
                        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.2f, context.resources.displayMetrics).toInt()
                        strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
                        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.card_bg))
                        elevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, context.resources.displayMetrics)
                        rippleColor = ColorStateList.valueOf(Color.parseColor("#33FF6900"))
                        isAllCaps = false
                        gravity = Gravity.CENTER

                        btnData.iconRes?.let { iconResId ->
                            icon = ContextCompat.getDrawable(context, iconResId)
                            iconSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics).toInt()
                            iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
                            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                            iconPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, context.resources.displayMetrics).toInt()
                        }

                        ViewAnimationExtensions.applySpringTouch(this)
                        setOnClickListener {
                            if (btnData.actionType == ChatActionType.EXECUTE_UNINSTALL_APP ||
                                btnData.actionType == ChatActionType.EXECUTE_UNINSTALL_BLOATWARE ||
                                btnData.actionType == ChatActionType.EXECUTE_15_FIX_COMMANDS ||
                                btnData.actionType == ChatActionType.EXECUTE_7_FLAGSHIP_COMMANDS ||
                                btnData.actionType == ChatActionType.EXECUTE_6_GPS_COMMANDS ||
                                btnData.actionType == ChatActionType.EXECUTE_RESET_ALL ||
                                btnData.actionType == ChatActionType.EXECUTE_CHANGE_VIETNAMESE_LOCALE ||
                                btnData.actionType == ChatActionType.CANCEL_PENDING_ACTION) {
                                clearConfirmationButtons()
                            }
                            onActionClick?.invoke(btnData)
                        }
                    }
                    holder.layoutActionButtons.addView(actionBtn)
                }
            } else {
                holder.layoutActionButtons.visibility = View.GONE
            }

            // Bind thanh đánh giá & đóng góp câu trả lời (Feedback Bar)
            if (position == 0 || msg.text.isBlank()) {
                holder.layoutFeedbackBar?.visibility = View.GONE
            } else {
                holder.layoutFeedbackBar?.visibility = View.VISIBLE

                // Nạp số lượng Like / Unlike từ cache nếu chưa có
                if (msg.likeCount == 0 && msg.unlikeCount == 0) {
                    val (l, u) = LimiKnowledgeBase.getVoteCounts(holder.itemView.context, msg.text)
                    msg.likeCount = l
                    msg.unlikeCount = u
                }

                // Cập nhật giao diện feedback
                bindFeedbackState(holder, msg)

                val onLikeAction = {
                    val currentPos = holder.adapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
                    if (msg.feedbackRating == 1) {
                        Toast.makeText(holder.itemView.context, "Bạn đã thích câu trả lời này! 👍", Toast.LENGTH_SHORT).show()
                    } else {
                        if (msg.feedbackRating == -1) {
                            msg.unlikeCount = (msg.unlikeCount - 1).coerceAtLeast(0)
                        }
                        msg.feedbackRating = 1
                        msg.likeCount++
                        LimiKnowledgeBase.saveVoteCounts(holder.itemView.context, msg.text, msg.likeCount, msg.unlikeCount)
                        bindFeedbackState(holder, msg)
                        notifyItemChanged(currentPos, PAYLOAD_FEEDBACK_ONLY)
                        onFeedbackThumbUp?.invoke(msg, currentPos)
                    }
                }

                val onUnlikeAction = {
                    val currentPos = holder.adapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
                    if (msg.feedbackRating == -1) {
                        Toast.makeText(holder.itemView.context, "Bạn đã phản hồi câu trả lời chưa hữu ích! 👎", Toast.LENGTH_SHORT).show()
                    } else {
                        if (msg.feedbackRating == 1) {
                            msg.likeCount = (msg.likeCount - 1).coerceAtLeast(0)
                        }
                        msg.feedbackRating = -1
                        msg.unlikeCount++
                        LimiKnowledgeBase.saveVoteCounts(holder.itemView.context, msg.text, msg.likeCount, msg.unlikeCount)
                        bindFeedbackState(holder, msg)
                        notifyItemChanged(currentPos, PAYLOAD_FEEDBACK_ONLY)
                        onFeedbackThumbDown?.invoke(msg, currentPos)
                    }
                }

                holder.btnFeedbackThumbUpContainer?.setOnClickListener { onLikeAction() }
                holder.btnFeedbackThumbUp?.setOnClickListener { onLikeAction() }

                holder.btnFeedbackThumbDownContainer?.setOnClickListener { onUnlikeAction() }
                holder.btnFeedbackThumbDown?.setOnClickListener { onUnlikeAction() }

                holder.btnFeedbackContribute?.setOnClickListener {
                    val currentPos = holder.adapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
                    onFeedbackContribute?.invoke(msg, currentPos)
                }
            }
        }
    }

    private fun bindFeedbackState(holder: LimiViewHolder, msg: LimiChatMessage) {
        holder.tvFeedbackThumbUpCount?.text = "${msg.likeCount}"
        holder.tvFeedbackThumbDownCount?.text = "${msg.unlikeCount}"

        when (msg.feedbackRating) {
            1 -> {
                holder.btnFeedbackThumbUp?.imageTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
                holder.tvFeedbackThumbUpCount?.setTextColor(Color.parseColor("#38BDF8"))
                holder.btnFeedbackThumbDown?.imageTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
                holder.tvFeedbackThumbDownCount?.setTextColor(Color.parseColor("#94A3B8"))
            }
            -1 -> {
                holder.btnFeedbackThumbUp?.imageTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
                holder.tvFeedbackThumbUpCount?.setTextColor(Color.parseColor("#94A3B8"))
                holder.btnFeedbackThumbDown?.imageTintList = ColorStateList.valueOf(Color.parseColor("#F43F5E"))
                holder.tvFeedbackThumbDownCount?.setTextColor(Color.parseColor("#F43F5E"))
            }
            else -> {
                holder.btnFeedbackThumbUp?.imageTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
                holder.tvFeedbackThumbUpCount?.setTextColor(Color.parseColor("#94A3B8"))
                holder.btnFeedbackThumbDown?.imageTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
                holder.tvFeedbackThumbDownCount?.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    fun getMessages(): List<LimiChatMessage> = messages.toList()

    fun setMessages(newMessages: List<LimiChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun addMessage(message: LimiChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessageText(newText: String) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            messages[lastIdx].text = newText
            notifyItemChanged(lastIdx, PAYLOAD_TEXT_ONLY)
        }
    }

    fun updateLastMessageExtras(buttons: List<ChatActionButton>, appPreview: AppPreviewInfo?, bloatwareList: List<BloatwareSelectionItem> = emptyList()) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            messages[lastIdx].actionButtons = buttons
            messages[lastIdx].appPreview = appPreview
            messages[lastIdx].bloatwareList = bloatwareList
            notifyItemChanged(lastIdx)
        }
    }

    fun completeLastMessage(
        fullText: String,
        buttons: List<ChatActionButton> = emptyList(),
        appPreview: AppPreviewInfo? = null,
        bloatwareList: List<BloatwareSelectionItem> = emptyList(),
        imageBitmap: android.graphics.Bitmap? = null,
        richSections: List<LimiChatSection> = emptyList()
    ) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            messages[lastIdx].text = fullText
            messages[lastIdx].actionButtons = buttons
            messages[lastIdx].appPreview = appPreview
            messages[lastIdx].bloatwareList = bloatwareList
            if (imageBitmap != null) {
                messages[lastIdx].imageBitmap = imageBitmap
            }
            if (richSections.isNotEmpty()) {
                messages[lastIdx].richSections = richSections
            }
            notifyItemChanged(lastIdx) // Full bind: hiện nút Like/Unlike & Góp ý & Phân đoạn ảnh ngay lập tức
        }
    }

    private fun createSectionImageCard(
        context: Context,
        bitmap: android.graphics.Bitmap,
        onImageClick: ((android.graphics.Bitmap) -> Unit)?
    ): View {
        val card = com.google.android.material.card.MaterialCardView(context).apply {
            radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, context.resources.displayMetrics)
            cardElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, context.resources.displayMetrics)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_bg))
            strokeColor = ContextCompat.getColor(context, R.color.card_stroke)
            strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, context.resources.displayMetrics).toInt()
                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, context.resources.displayMetrics).toInt()
            }
        }

        val frame = android.widget.FrameLayout(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val maxW = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 280f, context.resources.displayMetrics).toInt()
        val maxH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 220f, context.resources.displayMetrics).toInt()
        val minW = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 180f, context.resources.displayMetrics).toInt()

        val iv = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxWidth = maxW
            maxHeight = maxH
            minimumWidth = minW
            setImageBitmap(bitmap)
        }
        frame.addView(iv)

        // Badge "Chạm để xem ảnh"
        val badge = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_glass_pill)
            val pStart = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, context.resources.displayMetrics).toInt()
            val pTop = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, context.resources.displayMetrics).toInt()
            setPadding(pStart, pTop, pStart, pTop)
            val lp = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                setMargins(pStart, pStart, pStart, pStart)
            }
            layoutParams = lp
        }

        val icon = ImageView(context).apply {
            val s = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 11f, context.resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setImageResource(R.drawable.ic_fullscreen)
            setColorFilter(Color.WHITE)
        }
        badge.addView(icon)

        val tvBadge = TextView(context).apply {
            text = "Chạm để xem ảnh"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            val ms = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, context.resources.displayMetrics).toInt()
            setPadding(ms, 0, 0, 0)
        }
        badge.addView(tvBadge)

        frame.addView(badge)
        card.addView(frame)

        card.setOnClickListener {
            onImageClick?.invoke(bitmap)
        }

        return card
    }

    fun updateLastMessageButtons(buttons: List<ChatActionButton>) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            messages[lastIdx].actionButtons = buttons
            notifyItemChanged(lastIdx)
        }
    }

    fun getMessageAt(position: Int): LimiChatMessage? {
        return if (position in 0 until messages.size) messages[position] else null
    }

    fun updateMessageAt(position: Int, updatedMessage: LimiChatMessage) {
        if (position in 0 until messages.size) {
            messages[position] = updatedMessage
            notifyItemChanged(position)
        }
    }

    fun clearConfirmationButtons() {
        messages.forEachIndexed { index, msg ->
            if (msg.actionButtons.isNotEmpty()) {
                val hasConfirmOrCancel = msg.actionButtons.any {
                    it.actionType == ChatActionType.EXECUTE_UNINSTALL_APP ||
                    it.actionType == ChatActionType.EXECUTE_UNINSTALL_BLOATWARE ||
                    it.actionType == ChatActionType.EXECUTE_15_FIX_COMMANDS ||
                    it.actionType == ChatActionType.EXECUTE_7_FLAGSHIP_COMMANDS ||
                    it.actionType == ChatActionType.EXECUTE_6_GPS_COMMANDS ||
                    it.actionType == ChatActionType.EXECUTE_RESET_ALL ||
                    it.actionType == ChatActionType.EXECUTE_CHANGE_VIETNAMESE_LOCALE ||
                    it.actionType == ChatActionType.CANCEL_PENDING_ACTION
                }
                if (hasConfirmOrCancel) {
                    msg.actionButtons = emptyList()
                    notifyItemChanged(index)
                }
            }
        }
    }
}
