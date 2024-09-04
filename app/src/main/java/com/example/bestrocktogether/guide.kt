package com.example.bestrocktogether

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import io.noties.markwon.Markwon

class GuideDialogFragment : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_guide, container, false)

        val markdownText = """
           
    Voici quelques instructions pour utiliser l'application :
    - ***Étape 1*** : Faire ceci         
    - **Étape 2** : Faire cela
        
        
        
        
        
        

    - **Étape 3** : avec espace
""".trimIndent()

        val textView: TextView = view.findViewById(R.id.guide)
        val markwon = context?.let { Markwon.create(it) }
        if (markwon != null) {
            markwon.setMarkdown(textView, markdownText)
        }


        val closeButton: ImageButton = view.findViewById(R.id.close_button)
        closeButton.setOnClickListener {
            dismiss()
        }

        return view
    }
}
